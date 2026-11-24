use crate::{Error, Result};

#[derive(Clone, Copy)]
pub enum Unary {
    Group,
    Negate,
    Sqrt,
    Factorial,
    Percent,
    Sin,
    Cos,
    Tan,
    Asin,
    Acos,
    Atan,
    Ln,
    Log,
    Abs,
}

#[derive(Clone, Copy)]
pub enum Binary {
    Add,
    Subtract,
    Multiply,
    Divide,
    Power,
}

pub enum Expr<'a> {
    Decimal(&'a str),
    Saved(usize),
    Pi,
    Euler,
    Variable,
    Unary(Unary, Box<Self>),
    Binary(Binary, Box<Self>, Box<Self>),
}

impl Expr<'_> {
    pub fn bare_percent(&self) -> bool {
        match self {
            Self::Unary(Unary::Percent, _) => true,
            Self::Unary(Unary::Negate | Unary::Sqrt, value) => value.bare_percent(),
            _ => false,
        }
    }
}

pub fn parse(text: &str) -> Result<(Expr<'_>, Option<Expr<'_>>)> {
    if text.len() > 4096 {
        return Err(Error::Limit);
    }
    let mut parser = Parser {
        rest: text,
        nodes: 0,
        depth: 0,
    };
    let lhs = parser.sum()?;
    let rhs = if parser.eat('=') {
        Some(parser.sum()?)
    } else {
        None
    };
    if !parser.rest.is_empty() {
        return Err(if parser.rest.starts_with('=') {
            Error::Unsupported
        } else {
            Error::Invalid
        });
    }
    Ok((lhs, rhs))
}

struct Parser<'a> {
    rest: &'a str,
    nodes: u16,
    depth: u16,
}

impl<'a> Parser<'a> {
    fn eat(&mut self, c: char) -> bool {
        if let Some(rest) = self.rest.strip_prefix(c) {
            self.rest = rest;
            return true;
        }
        false
    }

    fn node(&mut self, expr: Expr<'a>) -> Result<Expr<'a>> {
        self.nodes += 1;
        if self.nodes > 192 {
            Err(Error::Limit)
        } else {
            Ok(expr)
        }
    }

    fn nested(&mut self, parse: impl FnOnce(&mut Self) -> Result<Expr<'a>>) -> Result<Expr<'a>> {
        if self.depth == 48 {
            return Err(Error::Limit);
        }
        self.depth += 1;
        let result = parse(self);
        self.depth -= 1;
        result
    }

    fn sum(&mut self) -> Result<Expr<'a>> {
        let mut left = self.product()?;
        loop {
            let op = if self.eat('+') {
                Binary::Add
            } else if self.eat('−') {
                Binary::Subtract
            } else {
                return Ok(left);
            };
            let right = self.product()?;
            left = self.node(Expr::Binary(op, Box::new(left), Box::new(right)))?;
        }
    }

    fn product(&mut self) -> Result<Expr<'a>> {
        let mut left = self.unary()?;
        while !self.rest.is_empty() && !self.rest.starts_with(['+', '−', ')', '=']) {
            let op = if self.eat('÷') {
                Binary::Divide
            } else if self.eat('×')
                || self
                    .rest
                    .starts_with(['(', 'π', 'e', 'x', '√', '@', 's', 'c', 't', 'l', 'a'])
            {
                Binary::Multiply
            } else {
                return Err(Error::Invalid);
            };
            let right = self.unary()?;
            left = self.node(Expr::Binary(op, Box::new(left), Box::new(right)))?;
        }
        Ok(left)
    }

    fn unary(&mut self) -> Result<Expr<'a>> {
        for (symbol, op) in [('−', Unary::Negate), ('√', Unary::Sqrt)] {
            if self.eat(symbol) {
                let value = self.nested(Self::unary)?;
                return self.node(Expr::Unary(op, Box::new(value)));
            }
        }

        let mut value = self.primary()?;
        loop {
            let op = if self.eat('!') {
                Unary::Factorial
            } else if self.eat('%') {
                Unary::Percent
            } else {
                break;
            };
            value = self.node(Expr::Unary(op, Box::new(value)))?;
        }
        if self.eat('^') {
            let exponent = self.nested(Self::unary)?;
            return self.node(Expr::Binary(
                Binary::Power,
                Box::new(value),
                Box::new(exponent),
            ));
        }
        Ok(value)
    }

    fn primary(&mut self) -> Result<Expr<'a>> {
        for (symbol, value) in [('π', Expr::Pi), ('e', Expr::Euler), ('x', Expr::Variable)] {
            if self.eat(symbol) {
                return self.node(value);
            }
        }
        if self.eat('@') {
            let index = self.digits().parse().map_err(|_| Error::Invalid)?;
            if !self.eat(';') {
                return Err(Error::Invalid);
            }
            return self.node(Expr::Saved(index));
        }

        if self.eat('(') {
            return self.argument(Unary::Group);
        }
        for (name, op) in [
            ("sin⁻¹(", Unary::Asin),
            ("cos⁻¹(", Unary::Acos),
            ("tan⁻¹(", Unary::Atan),
            ("sin(", Unary::Sin),
            ("cos(", Unary::Cos),
            ("tan(", Unary::Tan),
            ("ln(", Unary::Ln),
            ("log(", Unary::Log),
            ("abs(", Unary::Abs),
        ] {
            if let Some(rest) = self.rest.strip_prefix(name) {
                self.rest = rest;
                return self.argument(op);
            }
        }

        let start = self.rest;
        let whole = self.digits();
        let fraction = if self.eat('.') { self.digits() } else { "" };
        if whole.is_empty() && fraction.is_empty() {
            return Err(if self.rest.is_empty() {
                Error::Incomplete
            } else {
                Error::Invalid
            });
        }
        if self.eat('E') {
            if !self.eat('+') {
                self.eat('−');
            }
            if self.digits().is_empty() {
                return Err(if self.rest.is_empty() {
                    Error::Incomplete
                } else {
                    Error::Invalid
                });
            }
        }
        self.node(Expr::Decimal(&start[..start.len() - self.rest.len()]))
    }

    fn argument(&mut self, op: Unary) -> Result<Expr<'a>> {
        let value = self.nested(Self::sum)?;
        self.eat(')');
        self.node(Expr::Unary(op, Box::new(value)))
    }

    fn digits(&mut self) -> &'a str {
        let count = self.rest.bytes().take_while(u8::is_ascii_digit).count();
        let (digits, rest) = self.rest.split_at(count);
        self.rest = rest;
        digits
    }
}
