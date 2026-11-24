mod equation;
mod number;
mod parser;

use equation::Solution;
use jni::{
    objects::{JClass, JObjectArray, JString},
    sys::{jboolean, jlong, jstring, JNI_TRUE},
    JNIEnv,
};
use std::sync::atomic::{AtomicI64, Ordering};

#[derive(Clone, Copy, Debug)]
enum Error {
    Incomplete,
    Invalid,
    Domain,
    Unsupported,
    Limit,
    Cancelled,
}

impl Error {
    fn code(self) -> &'static str {
        match self {
            Self::Incomplete => "incomplete",
            Self::Invalid => "invalid",
            Self::Domain => "domain",
            Self::Unsupported => "unsupported",
            Self::Limit => "limit",
            Self::Cancelled => "cancelled",
        }
    }
}

type Result<T> = std::result::Result<T, Error>;

static GENERATION: AtomicI64 = AtomicI64::new(0);

fn check(id: i64) -> Result<()> {
    if GENERATION.load(Ordering::Relaxed) == id {
        Ok(())
    } else {
        Err(Error::Cancelled)
    }
}

fn calculate(text: &str, saved: &[String], radians: bool, id: i64) -> Result<String> {
    check(id)?;
    let (lhs, rhs) = parser::parse(text)?;
    let response = if let Some(rhs) = rhs {
        match equation::solve(&lhs, &rhs, saved, radians, id)? {
            Solution::All => "all".to_owned(),
            Solution::Empty => "empty".to_owned(),
            Solution::One(root) => format!("one\n{root}"),
            Solution::Two(first, second) => format!("two\n{first}\n{second}"),
            Solution::Radical {
                center,
                factor,
                radicand,
            } => format!("radical\n{center}\n{factor}\n{radicand}"),
        }
    } else {
        let number = number::evaluate(&lhs, saved, radians, id)?;
        let display = number.format()?;
        check(id)?;
        format!("number\n{display}\n{}", number.encode())
    };
    check(id)?;
    Ok(response)
}

#[no_mangle]
pub extern "system" fn Java_app_tantal_calculator_NativeEngine_setGeneration(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    GENERATION.store(id, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_app_tantal_calculator_NativeEngine_evaluate(
    mut env: JNIEnv,
    _: JClass,
    expression: JString,
    saved: JObjectArray,
    radians: jboolean,
    id: jlong,
) -> jstring {
    let mut read = || -> jni::errors::Result<String> {
        let expression: String = env.get_string(&expression)?.into();
        let count = env.get_array_length(&saved)?;
        if count > 16 {
            return Ok(Error::Limit.code().to_owned());
        }
        let mut values = Vec::with_capacity(count as usize);
        for i in 0..count {
            let value = JString::from(env.get_object_array_element(&saved, i)?);
            values.push(env.get_string(&value)?.into());
            env.delete_local_ref(value)?;
        }
        Ok(calculate(&expression, &values, radians == JNI_TRUE, id)
            .unwrap_or_else(|error| error.code().to_owned()))
    };
    match read().and_then(|answer| env.new_string(answer)) {
        Ok(answer) => answer.into_raw(),
        Err(error) => {
            eprintln!("Calculator JNI request failed: {error}");
            std::ptr::null_mut()
        }
    }
}
