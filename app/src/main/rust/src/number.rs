use crate::parser::{Binary, Expr, Unary};
use crate::{check, Error, Result};
use num_bigint::BigInt;
use num_rational::BigRational as Q;
use num_traits::{One, Signed, ToPrimitive, Zero};
use std::f64::consts::{E, PI};

const MAX_BITS: u64 = 2_000_000;
const MAX_EXPONENT: u32 = 600_000;

#[derive(Clone)]
pub enum Number {
    Rational(Q),
    Pi(Q),
    Approximate(f64),
}

pub fn integer(n: i32) -> Q {
    Q::from_integer(n.into())
}

fn ten(exponent: u32) -> BigInt {
    BigInt::from(10).pow(exponent)
}

pub fn decimal(text: &str) -> Result<Q> {
    let (mantissa, exponent) = text.split_once('E').unwrap_or((text, "0"));
    let exponent = exponent
        .replace('−', "-")
        .parse::<i32>()
        .map_err(|_| Error::Limit)?;
    let (whole, fraction) = mantissa.split_once('.').unwrap_or((mantissa, ""));
    let scale = exponent
        .checked_sub(fraction.len() as i32)
        .ok_or(Error::Limit)?;
    if scale.unsigned_abs() > MAX_EXPONENT {
        return Err(Error::Limit);
    }
    let n = format!("{whole}{fraction}")
        .parse::<BigInt>()
        .map_err(|_| Error::Invalid)?;
    if n.is_zero() {
        return Ok(Q::zero());
    }
    bounded(if scale >= 0 {
        Q::from_integer(n * ten(scale.unsigned_abs()))
    } else {
        Q::new(n, ten(scale.unsigned_abs()))
    })
}

pub fn bounded(q: Q) -> Result<Q> {
    if q.numer().bits().max(q.denom().bits()) > MAX_BITS {
        return Err(Error::Limit);
    }
    Ok(q)
}

pub fn rational_binary(op: Binary, left: Q, right: Q, id: i64) -> Result<Q> {
    check(id)?;
    bounded(match op {
        Binary::Add => left + right,
        Binary::Subtract => left - right,
        Binary::Multiply => left * right,
        Binary::Divide if !right.is_zero() => left / right,
        Binary::Divide => return Err(Error::Domain),
        Binary::Power if right.is_integer() => {
            let exponent = right.to_integer().to_i32().ok_or(Error::Limit)?;
            if exponent < 0 && left.is_zero() {
                return Err(Error::Domain);
            }
            let bits = left
                .numer()
                .bits()
                .max(left.denom().bits())
                .saturating_sub(1);
            if bits * u64::from(exponent.unsigned_abs()) > MAX_BITS {
                return Err(Error::Limit);
            }
            if left.is_zero() || left.is_one() || left == -Q::one() {
                return Ok(match exponent {
                    0 => Q::one(),
                    n if left == -Q::one() && n % 2 == 0 => Q::one(),
                    _ => left,
                });
            }
            let mut factor = if exponent < 0 { left.recip() } else { left };
            let mut power = exponent.unsigned_abs();
            let mut result = Q::one();
            while power != 0 {
                check(id)?;
                if power & 1 == 1 {
                    result = bounded(Q::new_raw(
                        result.numer() * factor.numer(),
                        result.denom() * factor.denom(),
                    ))?;
                }
                power >>= 1;
                if power != 0 {
                    factor = bounded(Q::new_raw(
                        factor.numer() * factor.numer(),
                        factor.denom() * factor.denom(),
                    ))?;
                }
            }
            result
        }
        Binary::Power => return Err(Error::Unsupported),
    })
}

fn product(first: u32, end: u32, id: i64) -> Result<BigInt> {
    check(id)?;
    if end - first <= 16 {
        return Ok((first..end).map(BigInt::from).product());
    }
    let middle = first + (end - first) / 2;
    Ok(product(first, middle, id)? * product(middle, end, id)?)
}

pub fn exact_sqrt(q: &Q) -> Option<Q> {
    if q.is_negative() {
        return None;
    }
    let n = q.numer().sqrt();
    let d = q.denom().sqrt();
    if &n * &n == *q.numer() && &d * &d == *q.denom() {
        Some(Q::new_raw(n, d))
    } else {
        None
    }
}

impl Number {
    fn float(&self) -> Result<f64> {
        let value = match self {
            Self::Rational(q) => q.to_f64(),
            Self::Pi(q) => q.to_f64().map(|value| value * PI),
            Self::Approximate(value) => Some(*value),
        };
        value.filter(|v| v.is_finite()).ok_or(Error::Limit)
    }

    fn approximate(value: f64) -> Result<Self> {
        if value.is_finite() {
            Ok(Self::Approximate(value))
        } else {
            Err(Error::Domain)
        }
    }

    pub fn decode(text: &str) -> Result<Self> {
        let (kind, value) = text.split_once(':').ok_or(Error::Invalid)?;
        match kind {
            "F" => Self::approximate(value.parse().map_err(|_| Error::Invalid)?),
            "Q" | "P" => {
                if value.len() > (MAX_BITS as usize / 4 + 1) * 2 + 2 {
                    return Err(Error::Limit);
                }
                let (n, d) = value.split_once('/').ok_or(Error::Invalid)?;
                let numerator = BigInt::parse_bytes(n.as_bytes(), 16).ok_or(Error::Invalid)?;
                let denominator = BigInt::parse_bytes(d.as_bytes(), 16).ok_or(Error::Invalid)?;
                if !denominator.is_positive() {
                    return Err(Error::Invalid);
                }
                // Persisted rationals are already reduced by the engine.
                let q = bounded(Q::new_raw(numerator, denominator))?;
                Ok(if kind == "P" {
                    Self::Pi(q)
                } else {
                    Self::Rational(q)
                })
            }
            _ => Err(Error::Invalid),
        }
    }

    pub fn encode(&self) -> String {
        match self {
            Self::Rational(q) | Self::Pi(q) => {
                let tag = if matches!(self, Self::Pi(_)) {
                    "P"
                } else {
                    "Q"
                };
                format!(
                    "{tag}:{}/{}",
                    q.numer().to_str_radix(16),
                    q.denom().to_str_radix(16)
                )
            }
            Self::Approximate(value) => format!("F:{value}"),
        }
    }

    pub fn format(&self) -> Result<String> {
        match self {
            Self::Rational(q) => Ok(format_decimal(q)),
            _ => {
                let q = Q::from_float(self.float()?).ok_or(Error::Domain)?;
                Ok(format_decimal(&q))
            }
        }
    }

    fn inverse_angle(op: Unary, q: &Q) -> Result<Option<Q>> {
        let one = Q::one();
        let half = Q::new(1.into(), 2.into());
        let ratio = |numerator: i32, denominator: i32| Q::new(numerator.into(), denominator.into());
        Ok(Some(match op {
            Unary::Asin if q.abs() > one => return Err(Error::Domain),
            Unary::Asin if q.is_zero() => Q::zero(),
            Unary::Asin if q.abs() == one => q * &half,
            Unary::Asin if q.abs() == half => q / integer(3),
            Unary::Acos if q.abs() > one => return Err(Error::Domain),
            Unary::Acos if q.is_one() => Q::zero(),
            Unary::Acos if *q == -one => Q::one(),
            Unary::Acos if q.is_zero() => half,
            Unary::Acos if *q == half => ratio(1, 3),
            Unary::Acos if *q == -half => ratio(2, 3),
            Unary::Atan if q.is_zero() => Q::zero(),
            Unary::Atan if q.abs().is_one() => q / integer(4),
            _ => return Ok(None),
        }))
    }

    pub fn unary(self, op: Unary, radians: bool, id: i64) -> Result<Self> {
        let exact = |n| Ok(Self::Rational(integer(n)));
        if let Self::Rational(q) = &self {
            if let Some(angle) = Self::inverse_angle(op, q)? {
                return Ok(if radians && !angle.is_zero() {
                    Self::Pi(angle)
                } else {
                    Self::Rational(angle * integer(180))
                });
            }
        }
        match (op, &self) {
            (Unary::Group, _) => return Ok(self),
            (Unary::Negate, Self::Rational(q)) => return Ok(Self::Rational(-q)),
            (Unary::Negate, Self::Pi(q)) => return Ok(Self::Pi(-q)),
            (Unary::Abs, Self::Rational(q)) => return Ok(Self::Rational(q.abs())),
            (Unary::Abs, Self::Pi(q)) => return Ok(Self::Pi(q.abs())),
            (Unary::Percent, _) => {
                return self.binary(Binary::Divide, Self::Rational(integer(100)), id)
            }
            (Unary::Factorial, Self::Rational(q)) if q.is_integer() => {
                if q.is_negative() {
                    return Err(Error::Domain);
                }
                let n = q
                    .to_integer()
                    .to_u32()
                    .filter(|&n| n <= 100_000)
                    .ok_or(Error::Limit)?;
                return Ok(Self::Rational(Q::from_integer(if n < 2 {
                    BigInt::one()
                } else {
                    product(2, n + 1, id)?
                })));
            }
            (Unary::Factorial, _) => return Err(Error::Domain),
            (Unary::Sqrt, Self::Rational(q)) if q.is_negative() => return Err(Error::Domain),
            (Unary::Sqrt, Self::Rational(q)) => {
                if let Some(root) = exact_sqrt(q) {
                    return Ok(Self::Rational(root));
                }
            }
            (Unary::Log | Unary::Ln, Self::Rational(q)) if q.is_one() => return exact(0),
            (Unary::Log | Unary::Ln, Self::Rational(q)) if !q.is_positive() => {
                return Err(Error::Domain)
            }
            _ => {}
        }

        if matches!(op, Unary::Sin | Unary::Cos | Unary::Tan) {
            let turns = match &self {
                Self::Rational(q) if !radians => Some(q / integer(180)),
                Self::Pi(q) if radians => Some(q.clone()),
                Self::Rational(q) if q.is_zero() => Some(Q::zero()),
                _ => None,
            };
            if let Some(turns) = turns {
                let reduced = turns % integer(2);
                let quadrant = &reduced * integer(2);
                if quadrant.is_integer() {
                    let q = quadrant
                        .to_integer()
                        .to_i32()
                        .ok_or(Error::Limit)?
                        .rem_euclid(4);
                    return match op {
                        Unary::Sin => exact([0, 1, 0, -1][q as usize]),
                        Unary::Cos => exact([1, 0, -1, 0][q as usize]),
                        _ if q % 2 == 0 => exact(0),
                        _ => Err(Error::Domain),
                    };
                }
                let sixth = &reduced * integer(6);
                if sixth.is_integer() {
                    let sixth = sixth
                        .to_integer()
                        .to_i32()
                        .ok_or(Error::Limit)?
                        .rem_euclid(12);
                    let half = match (op, sixth) {
                        (Unary::Sin, 1 | 5) | (Unary::Cos, 2 | 10) => Some(1),
                        (Unary::Sin, 7 | 11) | (Unary::Cos, 4 | 8) => Some(-1),
                        _ => None,
                    };
                    if let Some(n) = half {
                        return Ok(Self::Rational(Q::new(n.into(), 2.into())));
                    }
                }
                let nearest = quadrant.round().to_integer().to_i32().ok_or(Error::Limit)?;
                let offset = reduced - Q::new(nearest.into(), 2.into());
                let angle = Self::Rational(offset).float()? * PI;
                let (sin, cos) = angle.sin_cos();
                let (sin, cos) = match nearest.rem_euclid(4) {
                    0 => (sin, cos),
                    1 => (cos, -sin),
                    2 => (-sin, -cos),
                    _ => (-cos, sin),
                };
                return Self::approximate(match op {
                    Unary::Sin => sin,
                    Unary::Cos => cos,
                    _ => sin / cos,
                });
            }
        }

        let x = self.float()?;
        let angle = if radians { x } else { x.to_radians() };
        let from_angle = |value: f64| if radians { value } else { value.to_degrees() };
        Self::approximate(match op {
            Unary::Negate => -x,
            Unary::Abs => x.abs(),
            Unary::Sqrt => x.sqrt(),
            Unary::Sin => angle.sin(),
            Unary::Cos => angle.cos(),
            Unary::Tan => angle.tan(),
            Unary::Asin => from_angle(x.asin()),
            Unary::Acos => from_angle(x.acos()),
            Unary::Atan => from_angle(x.atan()),
            Unary::Ln => x.ln(),
            Unary::Log => x.log10(),
            _ => return Err(Error::Unsupported),
        })
    }

    pub fn binary(self, op: Binary, right: Self, id: i64) -> Result<Self> {
        check(id)?;
        match (op, self, right) {
            (Binary::Add | Binary::Subtract, Self::Pi(a), Self::Pi(b)) => {
                let q = rational_binary(op, a, b, id)?;
                Ok(if q.is_zero() {
                    Self::Rational(q)
                } else {
                    Self::Pi(q)
                })
            }
            (Binary::Multiply, Self::Pi(a), Self::Rational(b))
            | (Binary::Multiply, Self::Rational(b), Self::Pi(a))
            | (Binary::Divide, Self::Pi(a), Self::Rational(b)) => {
                let q = rational_binary(op, a, b, id)?;
                Ok(if q.is_zero() {
                    Self::Rational(q)
                } else {
                    Self::Pi(q)
                })
            }
            (Binary::Divide, Self::Pi(a), Self::Pi(b)) => {
                Ok(Self::Rational(rational_binary(op, a, b, id)?))
            }
            (Binary::Power, Self::Rational(a), Self::Rational(b))
                if b == Q::new(1.into(), 2.into()) =>
            {
                Self::Rational(a).unary(Unary::Sqrt, false, id)
            }
            (op, Self::Rational(a), Self::Rational(b))
                if !matches!(op, Binary::Power) || b.is_integer() =>
            {
                Ok(Self::Rational(rational_binary(op, a, b, id)?))
            }
            (op, left, right) => {
                let a = left.float()?;
                let b = right.float()?;
                Self::approximate(match op {
                    Binary::Add => a + b,
                    Binary::Subtract => a - b,
                    Binary::Multiply => a * b,
                    Binary::Divide => a / b,
                    Binary::Power => a.powf(b),
                })
            }
        }
    }
}

pub fn evaluate(expr: &Expr<'_>, saved: &[String], radians: bool, id: i64) -> Result<Number> {
    check(id)?;
    match expr {
        Expr::Decimal(text) => Ok(Number::Rational(decimal(text)?)),
        Expr::Saved(index) => Number::decode(saved.get(*index).ok_or(Error::Invalid)?),
        Expr::Pi => Ok(Number::Pi(Q::one())),
        Expr::Euler => Ok(Number::Approximate(E)),
        Expr::Variable => Err(Error::Unsupported),
        Expr::Unary(op, arg) => evaluate(arg, saved, radians, id)?.unary(*op, radians, id),
        Expr::Binary(op, left, right) => {
            let a = evaluate(left, saved, radians, id)?;
            let b = evaluate(right, saved, radians, id)?;
            if matches!(op, Binary::Add | Binary::Subtract) && right.bare_percent() {
                let percent = a.clone().binary(Binary::Multiply, b, id)?;
                a.binary(*op, percent, id)
            } else {
                a.binary(*op, b, id)
            }
        }
    }
}

fn scale_ratio(n: &BigInt, d: &BigInt, power: i32) -> (BigInt, BigInt) {
    if power >= 0 {
        (n.clone(), d * ten(power.unsigned_abs()))
    } else {
        (n * ten(power.unsigned_abs()), d.clone())
    }
}

fn format_decimal(q: &Q) -> String {
    if q.is_zero() {
        return "0".to_owned();
    }
    let n = q.numer().abs();
    let d = q.denom();
    let mut exponent =
        ((n.bits() as f64 - d.bits() as f64) * std::f64::consts::LOG10_2).floor() as i32;
    let (scaled_n, scaled_d) = scale_ratio(&n, d, exponent);
    if scaled_n < scaled_d {
        exponent -= 1;
    } else if scaled_n >= &scaled_d * 10 {
        exponent += 1;
    }

    let (scaled_n, scaled_d) = scale_ratio(&n, d, exponent - 11);
    let mut rounded: BigInt = (&scaled_n * 2 + &scaled_d) / (&scaled_d * 2);
    if rounded >= ten(12) {
        exponent += 1;
        rounded /= 10;
    }
    let digits = rounded.to_str_radix(10);
    let digits = digits.trim_end_matches('0');
    let sign = if q.is_negative() { "−" } else { "" };
    if (-7..=11).contains(&exponent) {
        let point = exponent + 1;
        if point <= 0 {
            format!("{sign}0.{}{digits}", "0".repeat((-point) as usize))
        } else {
            let point = point as usize;
            if point >= digits.len() {
                format!("{sign}{digits}{}", "0".repeat(point - digits.len()))
            } else {
                format!("{sign}{}.{}", &digits[..point], &digits[point..])
            }
        }
    } else {
        let (head, tail) = digits.split_at(1);
        let dot = if tail.is_empty() { "" } else { "." };
        format!(
            "{sign}{head}{dot}{tail}E{}",
            exponent.to_string().replace('-', "−")
        )
    }
}
