// RustShell JNI 桥接层
//
// 对应 Kotlin `com.newoether.agora.util.RustShell`
// 实现 nativeCreateShellClient / nativeFetchPublicKey / nativeExecuteCommand
//          nativeFileRead / nativeFileWrite / nativeFileGlob / nativeFileGrep
//          nativeDestroyShellClient

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jboolean, jlong, jstring, JNI_TRUE, JNI_FALSE};

use crate::jni::util;
use crate::shell::client::ShellClient;

use once_cell::sync::Lazy;

// ============================================================
// 全局 ShellClient 存储
//
// 每个 ShellClient 包装在 Arc<Mutex<>> 中，这样在 async 操作期间
// 只持有单个 client 的锁，而非全局 SHELL_CLIENTS 锁，
// 避免一个 shell 操作阻塞所有其他 shell client 的死锁问题。
// ============================================================

static NEXT_SHELL_HANDLE: std::sync::atomic::AtomicI64 =
    std::sync::atomic::AtomicI64::new(1);

static SHELL_CLIENTS: Lazy<Mutex<HashMap<i64, Arc<Mutex<ShellClient>>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

fn next_shell_handle() -> i64 {
    NEXT_SHELL_HANDLE.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
}

// ============================================================
// JNI 函数
// ============================================================

/// Java: nativeCreateShellClient(String serverUrl, String apiKey, String cachedKey) -> long
///
/// 创建 Shell 客户端，返回 opaque handle。负值表示错误。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeCreateShellClient(
    mut env: JNIEnv,
    _class: JClass,
    server_url: jni::objects::JString,
    api_key: jni::objects::JString,
    cached_key: jni::objects::JString,
) -> jlong {
    let Some(server_url) = util::extract_jstring(&mut env, server_url, "serverUrl") else {
        return -1;
    };
    let Some(api_key) = util::extract_jstring(&mut env, api_key, "apiKey") else {
        return -1;
    };
    let Some(cached_key) = util::extract_jstring(&mut env, cached_key, "cachedKey") else {
        return -1;
    };

    let client = ShellClient::new(server_url, api_key, cached_key);
    let handle = next_shell_handle();
    // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
    SHELL_CLIENTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(handle, Arc::new(Mutex::new(client)));

    log::info!("[JNI] Created shell client with handle {}", handle);
    handle
}

/// 从全局 map 中克隆 Arc<Mutex<ShellClient>> 出来，在 await 之前释放全局锁。
/// 返回 None 如果 handle 无效。
fn clone_shell_client(handle: jlong) -> Option<Arc<Mutex<ShellClient>>> {
    // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
    let clients = SHELL_CLIENTS.lock().unwrap_or_else(|e| e.into_inner());
    clients.get(&handle).map(|c| c.clone())
}

/// Java: nativeFetchPublicKey(long handle) -> boolean
///
/// 获取并验证服务端公钥。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeFetchPublicKey(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(client_arc) = clone_shell_client(handle) else {
        return JNI_FALSE;
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.fetch_public_key().await.unwrap_or(false)
    });

    if result {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Java: nativeExecuteCommand(long handle, String command, int timeoutMs, String workdir) -> String
///
/// 执行远程命令，返回 JSON: {"output": "...", "exit_code": N}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeExecuteCommand(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    command: jni::objects::JString,
    timeout_ms: jni::sys::jint,
    workdir: jni::objects::JString,
) -> jstring {
    let Some(command) = util::extract_jstring(&mut env, command, "command") else {
        return std::ptr::null_mut();
    };
    let Some(workdir) = util::extract_jstring(&mut env, workdir, "workdir") else {
        return std::ptr::null_mut();
    };

    let Some(client_arc) = clone_shell_client(handle) else {
        let response_str = serde_json::json!({"error": format!("Invalid shell handle: {}", handle)}).to_string();
        return match util::string_to_jstring(&mut env, &response_str) {
            Ok(s) => s,
            Err(_) => std::ptr::null_mut(),
        };
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.execute_command(&command, timeout_ms, &workdir).await
    });

    let response = match result {
        Ok(json_str) => {
            // 尝试解析并规范化为标准格式
            match serde_json::from_str::<serde_json::Value>(&json_str) {
                Ok(v) => v,
                Err(_) => serde_json::json!({"output": json_str, "exit_code": 0}),
            }
        }
        Err(e) => serde_json::json!({"error": e.to_string()}),
    };

    let response_str = response.to_string();
    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeFileRead(long handle, String path, long offset, long limit) -> String
///
/// 读取远程文件，返回 JSON: {"content": "...", "lines": N, "totalLines": M}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeFileRead(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: jni::objects::JString,
    offset: jlong,
    limit: jlong,
) -> jstring {
    let Some(path) = util::extract_jstring(&mut env, path, "path") else {
        return std::ptr::null_mut();
    };

    let Some(client_arc) = clone_shell_client(handle) else {
        let response_str = serde_json::json!({"error": format!("Invalid shell handle: {}", handle)}).to_string();
        return match util::string_to_jstring(&mut env, &response_str) {
            Ok(s) => s,
            Err(_) => std::ptr::null_mut(),
        };
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.file_read(&path, offset, limit).await
    });

    let response_str = match result {
        Ok(file_result) => serde_json::to_string(&file_result).unwrap_or_default(),
        Err(e) => serde_json::json!({"error": e.to_string()}).to_string(),
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeFileWrite(long handle, String path, String content) -> String
///
/// 写入远程文件，返回 JSON: {} 或 {"error": "..."}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeFileWrite(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: jni::objects::JString,
    content: jni::objects::JString,
) -> jstring {
    let Some(path) = util::extract_jstring(&mut env, path, "path") else {
        return std::ptr::null_mut();
    };
    let Some(content) = util::extract_jstring(&mut env, content, "content") else {
        return std::ptr::null_mut();
    };

    let Some(client_arc) = clone_shell_client(handle) else {
        let response_str = serde_json::json!({"error": format!("Invalid shell handle: {}", handle)}).to_string();
        return match util::string_to_jstring(&mut env, &response_str) {
            Ok(s) => s,
            Err(_) => std::ptr::null_mut(),
        };
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.file_write(&path, &content).await
    });

    let response_str = match result {
        Ok(error_opt) => {
            if let Some(error) = error_opt {
                serde_json::json!({"error": error}).to_string()
            } else {
                "{}".to_string()
            }
        }
        Err(e) => serde_json::json!({"error": e.to_string()}).to_string(),
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeFileGlob(long handle, String pattern, String basePath) -> String
///
/// Glob 文件搜索，返回 JSON: {"files": [...]}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeFileGlob(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pattern: jni::objects::JString,
    base_path: jni::objects::JString,
) -> jstring {
    let Some(pattern) = util::extract_jstring(&mut env, pattern, "pattern") else {
        return std::ptr::null_mut();
    };
    let Some(base_path) = util::extract_jstring(&mut env, base_path, "basePath") else {
        return std::ptr::null_mut();
    };

    let Some(client_arc) = clone_shell_client(handle) else {
        let response_str = serde_json::json!({"error": format!("Invalid shell handle: {}", handle)}).to_string();
        return match util::string_to_jstring(&mut env, &response_str) {
            Ok(s) => s,
            Err(_) => std::ptr::null_mut(),
        };
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.file_glob(&pattern, &base_path, None).await
    });

    let response_str = match result {
        Ok(files) => serde_json::json!({"files": files}).to_string(),
        Err(e) => serde_json::json!({"error": e.to_string()}).to_string(),
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeFileGrep(long handle, String pattern, String basePath) -> String
///
/// Grep 文件内容搜索，返回 JSON: {"matches": [{"path": "...", "line": N, "content": "..."}]}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeFileGrep(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    pattern: jni::objects::JString,
    base_path: jni::objects::JString,
) -> jstring {
    let Some(pattern) = util::extract_jstring(&mut env, pattern, "pattern") else {
        return std::ptr::null_mut();
    };
    let Some(base_path) = util::extract_jstring(&mut env, base_path, "basePath") else {
        return std::ptr::null_mut();
    };

    let Some(client_arc) = clone_shell_client(handle) else {
        let response_str = serde_json::json!({"error": format!("Invalid shell handle: {}", handle)}).to_string();
        return match util::string_to_jstring(&mut env, &response_str) {
            Ok(s) => s,
            Err(_) => std::ptr::null_mut(),
        };
    };

    let result = util::get_global_runtime().block_on(async {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        let mut client = client_arc.lock().unwrap_or_else(|e| e.into_inner());
        client.file_grep(&pattern, &base_path, "").await
    });

    let response_str = match result {
        Ok(matches) => serde_json::json!({"matches": matches}).to_string(),
        Err(e) => serde_json::json!({"error": e.to_string()}).to_string(),
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeDestroyShellClient(long handle)
///
/// 释放 Shell 客户端。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_util_RustShell_nativeDestroyShellClient(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle > 0 {
        // 使用 unwrap_or_else 恢复中毒的 Mutex，避免 panic 跨越 FFI 边界导致 JVM 崩溃。
        SHELL_CLIENTS
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
        log::info!("[JNI] Destroyed shell client with handle {}", handle);
    }
}
