use crate::number::{bounded, evaluate, exact_sqrt, integer, rational_binary, Number};
use crate::parser::{Binary, Expr, Unary};
use crate::{check, Error, Result};
use num_bigint::BigInt;
use num_rational::BigRational as Q;
use num_traits::{One, Signed, ToPrimitive, Zero};

#[derive(Clone)]
struct Polynomial([Q; 3]);

impl Polynomial {
    fn constant(q: Q) -> Self {
        Self([q, Q::zero(), Q::zero()])
    }

    fn add(self, other: Self, op: Binary, id: i64) -> Result<Self> {
        let [c, b, a] = self.0;
        let [z, y, x] = other.0;
        Ok(Self([
            rational_binary(op, c, z, id)?,
            rational_binary(op, b, y, id)?,
            rational_binary(op, a, x, id)?,
        ]))
    }

    fn multiply(&self, other: &Self, id: i64) -> Result<Self> {
        let mut product = Self::constant(Q::zero());
        for (i, left) in self.0.iter().enumerate().filter(|(_, q)| !q.is_zero()) {
            for (j, right) in other.0.iter().enumerate().filter(|(_, q)| !q.is_zero()) {
                let coefficient = product.0.get_mut(i + j).ok_or(Error::Unsupported)?;
                check(id)?;
                *coefficient = bounded(&*coefficient + left * right)?;
            }
        }
        Ok(product)
    }

    fn divide(self, divisor: &Q, id: i64) -> Result<Self> {
        let [c, b, a] = self.0;
        Ok(Self([
            rational_binary(Binary::Divide, c, divisor.clone(), id)?,
            rational_binary(Binary::Divide, b, divisor.clone(), id)?,
            rational_binary(Binary::Divide, a, divisor.clone(), id)?,
        ]))
    }

    fn scalar(&self) -> Result<&Q> {
        if self.0[1..].iter().all(Zero::is_zero) {
            Ok(&self.0[0])
        } else {
            Err(Error::Unsupported)
        }
    }
}

fn contains_variable(expr: &Expr<'_>) -> bool {
    match expr {
        Expr::Variable => true,
        Expr::Unary(_, arg) => contains_variable(arg),
        Expr::Binary(_, left, right) => contains_variable(left) || contains_variable(right),
        _ => false,
    }
}

fn collect(expr: &Expr<'_>, saved: &[String], radians: bool, id: i64) -> Result<Polynomial> {
    check(id)?;
    if !contains_variable(expr) {
        return match evaluate(expr, saved, radians, id)? {
            Number::Rational(q) => Ok(Polynomial::constant(q)),
            _ => Err(Error::Unsupported),
        };
    }
    match expr {
        Expr::Variable => Ok(Polynomial([Q::zero(), Q::one(), Q::zero()])),
        Expr::Unary(op, value) => {
            let poly = collect(value, saved, radians, id)?;
            match op {
                Unary::Group => Ok(poly),
                Unary::Negate => Ok(Polynomial(poly.0.map(|q| -q))),
                Unary::Percent => poly.divide(&integer(100), id),
                _ => match Number::Rational(poly.scalar()?.clone()).unary(*op, radians, id)? {
                    Number::Rational(q) => Ok(Polynomial::constant(q)),
                    _ => Err(Error::Unsupported),
                },
            }
        }
        Expr::Binary(op, left, right) => {
            let a = collect(left, saved, radians, id)?;
            let b = collect(right, saved, radians, id)?;
            match op {
                Binary::Add | Binary::Subtract => {
                    let term = if right.bare_percent() {
                        a.multiply(&b, id)?
                    } else {
                        b
                    };
                    a.add(term, *op, id)
                }
                Binary::Multiply => a.multiply(&b, id),
                Binary::Divide => a.divide(b.scalar()?, id),
                Binary::Power => {
                    let exponent = b.scalar()?;
                    if let Ok(base) = a.scalar() {
                        return match Number::Rational(base.clone()).binary(
                            Binary::Power,
                            Number::Rational(exponent.clone()),
                            id,
                        )? {
                            Number::Rational(q) => Ok(Polynomial::constant(q)),
                            _ => Err(Error::Unsupported),
                        };
                    }
                    if !exponent.is_integer() {
                        return Err(Error::Unsupported);
                    }
                    match exponent.to_integer().to_u32() {
                        Some(0) => Ok(Polynomial::constant(Q::one())),
                        Some(1) => Ok(a),
                        Some(2) => a.multiply(&a, id),
                        _ => Err(Error::Unsupported),
                    }
                }
            }
        }
        _ => Err(Error::Unsupported),
    }
}

pub enum Solution {
    All,
    Empty,
    One(String),
    Two(String, String),
    Radical {
        center: String,
        factor: String,
        radicand: String,
    },
}

pub fn solve(
    lhs: &Expr<'_>,
    rhs: &Expr<'_>,
    saved: &[String],
    radians: bool,
    id: i64,
) -> Result<Solution> {
    let [c, b, a] = collect(lhs, saved, radians, id)?
        .add(collect(rhs, saved, radians, id)?, Binary::Subtract, id)?
        .0;

    if a.is_zero() {
        return if !b.is_zero() {
            Ok(Solution::One(fraction(&bounded(-c / b)?)?))
        } else if c.is_zero() {
            Ok(Solution::All)
        } else {
            Ok(Solution::Empty)
        };
    }
    let center = bounded(-b / (&a * integer(2)))?;
    let delta = bounded(&center * &center - c / a)?;
    if delta.is_negative() {
        return Ok(Solution::Empty);
    }
    if let Some(root) = exact_sqrt(&delta) {
        return if root.is_zero() {
            Ok(Solution::One(fraction(&center)?))
        } else {
            Ok(Solution::Two(
                fraction(&(&center - &root))?,
                fraction(&(center + root))?,
            ))
        };
    }
    radical(delta, center, id)
}

fn fraction(q: &Q) -> Result<String> {
    if q.numer().bits().max(q.denom().bits()) > 1024 {
        return Err(Error::Limit);
    }
    let numerator = q.numer().to_string().replace('-', "−");
    if q.is_integer() {
        Ok(numerator)
    } else {
        Ok(format!("{numerator}/{}", q.denom()))
    }
}

fn radical(delta: Q, center: Q, id: i64) -> Result<Solution> {
    if delta.numer().bits() + delta.denom().bits() > 1024 {
        return Err(Error::Limit);
    }
    let mut radicand = delta.numer() * delta.denom();
    let mut factor = BigInt::one();
    for candidate in 2..=1024 {
        check(id)?;
        let square = BigInt::from(candidate * candidate);
        if square > radicand {
            break;
        }
        while (&radicand % &square).is_zero() {
            radicand /= &square;
            factor *= candidate;
        }
    }
    let root = radicand.sqrt();
    if &root * &root == radicand {
        factor *= root;
        radicand = BigInt::one();
    }
    let multiplier = Q::new(factor, delta.denom().clone());
    Ok(Solution::Radical {
        center: if center.is_zero() {
            String::new()
        } else {
            fraction(&center)?
        },
        factor: if multiplier.is_one() {
            String::new()
        } else {
            fraction(&multiplier)?
        },
        radicand: radicand.to_string(),
    })
}
