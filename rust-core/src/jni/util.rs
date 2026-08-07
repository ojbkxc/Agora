use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JObject, JString, JValue};
use jni::sys::jstring;
use log::error;
use tokio::runtime::Runtime;

use crate::error::AgoraError;

/// 全局 tokio 多线程运行时，延迟初始化单例。
///
/// 包裹在 `Option` 中：若 `Runtime::new()` 失败（极端环境，如线程/资源限制），
/// 用 `None` 表示运行时不可用，避免 `expect` panic 跨越 FFI 边界导致 JVM 立即闪退
/// （panic=abort 不产生 Java 异常栈）。所有调用方必须 match 处理 `None`。
static RUNTIME: OnceLock<Option<Runtime>> = OnceLock::new();

/// 获取全局 tokio 多线程运行时引用。
///
/// 返回 `None` 表示运行时初始化失败，调用方应优雅降级（抛 Java 异常并返回 null）。
/// 不会 panic。
pub fn get_global_runtime() -> Option<&'static Runtime> {
    RUNTIME
        .get_or_init(|| match Runtime::new() {
            Ok(rt) => Some(rt),
            Err(e) => {
                error!("[JNI] Failed to create tokio multi-thread runtime: {}", e);
                None
            }
        })
        .as_ref()
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

/// 在 JNI 调用中执行闭包并将 `Result` 错误转换为 Java 异常。
///
/// 返回 `Some(T)` 表示成功，`None` 表示失败（已抛出 Java 异常）。
///
/// 注意：此函数**不捕获 Rust panic**。在 `panic = "abort"` profile（见 Cargo.toml
/// release 配置）下，`std::panic::catch_unwind` 无法捕获 panic，任何 panic 都会
/// 直接 abort 进程。调用方必须确保闭包内部不会 panic（例如用
/// `unwrap_or_else(|e| e.into_inner())` 恢复中毒的 Mutex，而非 `unwrap()`）。
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
