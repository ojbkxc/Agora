use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JObject, JString, JValue};
use jni::sys::jstring;
use log::error;
use tokio::runtime::Runtime;

use crate::error::AgoraError;

/// 全局 tokio 多线程运行时，延迟初始化单例
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// 获取全局 tokio 多线程运行时引用
pub fn get_global_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Runtime::new().expect("Failed to create tokio multi-thread runtime")
    })
}

/// 将 Java JString 转换为 Rust String
///
/// # Safety
/// 调用方必须确保 `env` 有效且 `jstr` 是一个合法的 jstring 引用。
pub fn jstring_to_string(env: &mut JNIEnv, jstr: JString) -> Result<String, AgoraError> {
    let jstr_ref = env.get_string(&jstr)
        .map_err(|e| AgoraError::Jni(format!("Failed to get JString: {}", e)))?;
    Ok(jstr_ref.into())
}

/// 将 Rust &str 转换为 Java JString
///
/// # Safety
/// 调用方必须确保 `env` 有效。
pub fn string_to_jstring(env: &mut JNIEnv, s: &str) -> Result<jstring, AgoraError> {
    let jstr = env.new_string(s)
        .map_err(|e| AgoraError::Jni(format!("Failed to create JString: {}", e)))?;
    Ok(jstr.into_raw())
}

/// 抛出 Java RuntimeException
pub fn throw_runtime_exception(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

/// 将 AgoraError 转换为 Java 异常并抛出
///
/// 根据错误类型映射到对应的 Java 异常类：
/// - 其他错误 → RuntimeException
pub fn handle_error(env: &mut JNIEnv, error: AgoraError) {
    error!("[JNI] Error: {}", error);
    let message = error.to_string();
    let _ = env.throw_new("java/lang/RuntimeException", &message);
}

/// 从 JNI 环境中安全提取 jstring 参数为 Rust String。
/// 如果提取失败，抛出异常并返回 None。
pub fn extract_jstring(
    env: &mut JNIEnv,
    jstr: JString,
    param_name: &str,
) -> Option<String> {
    match jstring_to_string(env, jstr) {
        Ok(s) => Some(s),
        Err(e) => {
            handle_error(env, AgoraError::Jni(format!(
                "Failed to read parameter '{}': {}", param_name, e
            )));
            None
        }
    }
}

/// 在 JNI 调用中捕获 Rust panic，防止 unwind 跨越 FFI 边界。
/// 返回 Ok(T) 或者将 panic 转换为 Java 异常并返回 Err(())。
pub fn catch_unwind_jni<F, T>(env: &mut JNIEnv, f: F) -> Option<T>
where
    F: FnOnce(&mut JNIEnv) -> Result<T, AgoraError>,
{
    match f(env) {
        Ok(val) => Some(val),
        Err(err) => {
            handle_error(env, err);
            None
        }
    }
}
