// RustProvider JNI 桥接层
//
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};

use jni::JNIEnv;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring};
use jni::JavaVM;

use crate::api::http_client::AgoraHttpClient;
use crate::api::provider::LlmProvider;
use crate::api::types::{ChatMessage, ProviderConfig, StreamEvent};
use crate::api::{
    AnthropicProvider, GeminiProvider, OllamaProvider, OpenAiProvider,
};
use crate::error::AgoraError;
use crate::jni::util;
use crate::model::ModelId;

use once_cell::sync::Lazy;

// ============================================================
// 全局 Provider 存储
// ============================================================

/// 生成单调递增的 handle ID
static NEXT_HANDLE: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

/// 存储所有活跃的 Provider 实例
static PROVIDERS: Lazy<Mutex<HashMap<i64, Arc<dyn LlmProvider>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// 存储 Provider 的持久化配置（API key、base_url 等）
static PROVIDER_CONFIGS: Lazy<Mutex<HashMap<i64, ProviderConfig>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// 每个 handle 关联一个取消令牌和回调存活标志。
/// 当 nativeDestroyProvider 被调用时，设置 cancelled = true 并移除 provider，
/// 正在运行的生成任务会在下一个 await 点检测到取消并安全退出。
static HANDLE_STATE: Lazy<Mutex<HashMap<i64, HandleState>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// Per-handle cancellation and callback lifecycle state.
struct HandleState {
    /// Set to true when nativeDestroyProvider is called.
    /// The running generation task checks this and aborts gracefully.
    cancelled: Arc<AtomicBool>,
    /// Set to true after the callback has been invalidated.
    /// Prevents JNI calls into a recycled Kotlin callback object.
    callback_invalid: Arc<AtomicBool>,
}

/// 安全获取 Mutex 锁，即使互斥锁被毒化也恢复数据。
/// 避免因其他线程 panic 导致当前线程也 panic（连锁崩溃）。
fn safe_lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// 分配新 handle
fn next_handle() -> i64 {
    NEXT_HANDLE.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
}

/// 存储 Provider 实例并返回 handle
fn store_provider(provider: Arc<dyn LlmProvider>, config: ProviderConfig) -> i64 {
    let handle = next_handle();
    safe_lock(&PROVIDERS).insert(handle, provider);
    safe_lock(&PROVIDER_CONFIGS).insert(handle, config);
    safe_lock(&HANDLE_STATE).insert(handle, HandleState {
        cancelled: Arc::new(AtomicBool::new(false)),
        callback_invalid: Arc::new(AtomicBool::new(false)),
    });
    handle
}

/// 移除 Provider 并释放资源。
/// 设置取消标志，使正在运行的生成任务在下一个 await 点安全退出。
fn remove_provider(handle: i64) {
    // Set the cancelled flag first so the running generation (if any) sees it.
    if let Some(state) = safe_lock(&HANDLE_STATE).get(&handle) {
        state.cancelled.store(true, Ordering::SeqCst);
        state.callback_invalid.store(true, Ordering::SeqCst);
    }
    safe_lock(&PROVIDERS).remove(&handle);
    safe_lock(&PROVIDER_CONFIGS).remove(&handle);
    // Keep the HandleState entry so nativeGenerate can observe the cancelled flag;
    // it will be cleaned up after nativeGenerate returns (or never, if the process
    // dies — which is fine, the HashMap is process-scoped anyway).
}

// ============================================================
// JNI 函数
// ============================================================

/// Java: nativeCreateProvider(String providerType, String configJson) -> long
///
/// 创建 Rust 侧 Provider 实例，返回 opaque handle。
/// 返回负值表示错误码。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_api_RustProvider_nativeCreateProvider(
    mut env: JNIEnv,
    _class: JClass,
    provider_type: JString,
    config_json: JString,
) -> jlong {
    let Some(provider_type) = util::extract_jstring(&mut env, provider_type, "providerType") else {
        return -1;
    };
    let Some(config_json) = util::extract_jstring(&mut env, config_json, "configJson") else {
        return -1;
    };

    // 解析配置
    let config: ProviderConfig = match serde_json::from_str(&config_json) {
        Ok(c) => c,
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Parse(format!("Failed to parse config: {}", e)));
            return -2;
        }
    };

    // 根据 provider_type 创建对应的 Provider
    let provider: Arc<dyn LlmProvider> = match provider_type.as_str() {
        "openai" => {
            let model_id = ModelId::parse(&config.model_id);
            match model_id.provider() {
                crate::model::PROVIDER_DEEPSEEK => Arc::new(OpenAiProvider::new_deepseek()),
                crate::model::PROVIDER_GROQ => Arc::new(OpenAiProvider::new_groq()),
                crate::model::PROVIDER_QWEN => Arc::new(OpenAiProvider::new_qwen()),
                crate::model::PROVIDER_OPENROUTER => Arc::new(OpenAiProvider::new_openrouter()),
                crate::model::PROVIDER_UNKNOWN => {
                    // 自定义端点
                    let base_url = config
                        .base_url
                        .clone()
                        .unwrap_or_else(|| "https://api.openai.com/v1".to_string());
                    Arc::new(OpenAiProvider::new_custom("Custom".to_string(), base_url))
                }
                _ => Arc::new(OpenAiProvider::new_openai()),
            }
        }
        "anthropic" => Arc::new(AnthropicProvider::new()),
        "gemini" => Arc::new(GeminiProvider::new()),
        "ollama" => Arc::new(OllamaProvider::new()),
        _ => {
            util::handle_error(
                &mut env,
                AgoraError::Config(format!("Unknown provider type: {}", provider_type)),
            );
            return -3;
        }
    };

    let handle = store_provider(provider, config);
    log::info!("[JNI] Created provider '{}' with handle {}", provider_type, handle);
    handle
}

/// Java: nativeGenerate(long handle, String messagesJson, String configJson, RustStreamCallback callback) -> String
///
/// 执行流式生成，通过回调实时推送事件。返回最终 JSON 摘要。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_api_RustProvider_nativeGenerate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    messages_json: JString,
    config_json: JString,
    callback: JObject,
) -> jstring {
    let Some(messages_json) = util::extract_jstring(&mut env, messages_json, "messagesJson") else {
        return std::ptr::null_mut();
    };
    let Some(_config_json) = util::extract_jstring(&mut env, config_json, "configJson") else {
        return std::ptr::null_mut();
    };

    // 解析消息
    let messages: Vec<ChatMessage> = match serde_json::from_str(&messages_json) {
        Ok(m) => m,
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Parse(format!("Failed to parse messages: {}", e)));
            return std::ptr::null_mut();
        }
    };

    // 获取 Provider 配置
    let config = {
        let configs = safe_lock(&PROVIDER_CONFIGS);
        match configs.get(&handle) {
            Some(c) => c.clone(),
            None => {
                util::handle_error(&mut env, AgoraError::Config(format!("Invalid handle: {}", handle)));
                return std::ptr::null_mut();
            }
        }
    };

    // 创建全局引用保持回调存活
    let callback_ref = match env.new_global_ref(callback) {
        Ok(r) => r,
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Jni(format!("Failed to create global ref: {}", e)));
            return std::ptr::null_mut();
        }
    };

    // 获取 JavaVM 用于在回调中获取 JNIEnv
    let jvm = match env.get_java_vm() {
        Ok(jvm) => Arc::new(jvm),
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Jni(format!("Failed to get JavaVM: {}", e)));
            return std::ptr::null_mut();
        }
    };

    // 创建 HTTP 客户端
    let http_client = AgoraHttpClient::new();

    // 获取该 handle 的取消标志和回调存活标志
    let (cancelled, callback_invalid) = {
        let state = safe_lock(&HANDLE_STATE);
        match state.get(&handle) {
            Some(s) => (s.cancelled.clone(), s.callback_invalid.clone()),
            None => {
                // Handle already destroyed — return immediately without touching callback.
                let error_json = AgoraError::Config(format!("Provider not found for handle: {}", handle)).to_json_string();
                return match util::string_to_jstring(&mut env, &error_json) {
                    Ok(s) => s,
                    Err(_) => std::ptr::null_mut(),
                };
            }
        }
    };

    // 创建流式回调：通过 JNI 实时推送事件到 Kotlin。
    // 在每次回调前检查 callback_invalid 标志，避免在已销毁的回调对象上调用 JNI。
    let on_event: crate::api::provider::StreamCallback = {
        let jvm = Arc::clone(&jvm);
        let cb = callback_ref.clone();
        let ci = callback_invalid.clone();
        Box::new(move |event: StreamEvent| {
            // 如果回调已失效（provider 被销毁），不再向 Kotlin 发送事件。
            if ci.load(Ordering::SeqCst) {
                return;
            }
            let mut env = match jvm.attach_current_thread() {
                Ok(env) => env,
                Err(e) => {
                    log::error!("[JNI] Failed to attach thread for callback: {}", e);
                    return;
                }
            };
            let event_json = match serde_json::to_string(&event) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("[JNI] Failed to serialize event: {}", e);
                    return;
                }
            };
            let jstr = match env.new_string(&event_json) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("[JNI] Failed to create JNI string: {}", e);
                    return;
                }
            };
            if let Err(e) = env.call_method(
                cb.as_obj(),
                "onEvent",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jstr)],
            ) {
                log::error!("[JNI] Failed to invoke callback.onEvent: {}", e);
            }
        })
    };

    // 在 tokio 运行时中 spawn 异步生成任务（非阻塞），通过 oneshot 通道等待结果。
    // 这避免了 block_on 在 JNI 线程上的死锁风险，并允许 nativeDestroyProvider
    // 通过设置 cancelled 标志来安全取消正在运行的生成任务。
    let (tx, rx) = tokio::sync::oneshot::channel();
    let cancelled_for_task = cancelled.clone();

    util::get_global_runtime().spawn(async move {
        // 检查是否在 spawn 之前就被取消了
        if cancelled_for_task.load(Ordering::SeqCst) {
            let _ = tx.send(Err(AgoraError::Config("Provider cancelled before generation started".to_string())));
            return;
        }

        // Clone the Arc out of the lock and drop the guard BEFORE awaiting,
        // so other JNI threads can access PROVIDERS concurrently.
        let provider = {
            let providers = safe_lock(&PROVIDERS);
            match providers.get(&handle) {
                Some(p) => p.clone(),
                None => {
                    let _ = tx.send(Err(AgoraError::Config(format!("Provider not found for handle: {}", handle))));
                    return;
                }
            }
        };

        // 在后台运行生成任务，同时监控取消标志
        let gen_fut = provider.generate_response(&messages, &config, &http_client, &on_event);

        // 使用 tokio::select! 在生成完成和取消标志之间竞争。
        // select! 会轮询两个 future，先完成的分支胜出。
        let result = tokio::select! {
            // 生成完成（正常或错误）
            res = gen_fut => res,
            // 检测到取消标志 — 生成会在 drop 时被中止
            _ = async {
                while !cancelled_for_task.load(Ordering::SeqCst) {
                    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                }
            } => {
                Err(AgoraError::Config("Generation cancelled by nativeDestroyProvider".to_string()))
            }
        };

        let _ = tx.send(result);
    });

    // 阻塞等待生成结果（oneshot 通道），但这不会导致 Tokio 死锁，
    // 因为生成任务运行在 Tokio 的 spawn 线程池上，而不是当前线程。
    let result = rx.blocking_recv();

    match result {
        Ok(Ok(())) => {
            // 返回完成状态
            let summary = serde_json::json!({
                "status": "completed"
            });
            let summary_str = summary.to_string();
            match util::string_to_jstring(&mut env, &summary_str) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            }
        }
        Ok(Err(e)) => {
            // 推送错误事件
            let error_event = serde_json::json!({
                "type": "error",
                "data": {
                    "error": {
                        "type": "unknown",
                        "message": e.to_string()
                    }
                }
            });
            // 通过回调推送错误
            {
                let mut env = match jvm.attach_current_thread() {
                    Ok(env) => env,
                    Err(_) => {
                        log::error!("[JNI] Failed to attach thread for error callback");
                        return std::ptr::null_mut();
                    }
                };
                let jstr = match env.new_string(&error_event.to_string()) {
                    Ok(s) => s,
                    Err(_) => return std::ptr::null_mut(),
                };
                let _ = env.call_method(
                    callback_ref.as_obj(),
                    "onEvent",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&jstr)],
                );
            }

            // 返回错误 JSON
            let error_json = e.to_json_string();
            match util::string_to_jstring(&mut env, &error_json) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(_) => {
            // oneshot 发送端异常关闭（生成任务 panic 或被丢弃）
            log::error!("[JNI] Generation task dropped without sending a result");
            let error_event = serde_json::json!({
                "type": "error",
                "data": {
                    "error": {
                        "type": "unknown",
                        "message": "Generation task terminated unexpectedly"
                    }
                }
            });
            {
                let mut env = match jvm.attach_current_thread() {
                    Ok(env) => env,
                    Err(_) => {
                        return std::ptr::null_mut();
                    }
                };
                if let Ok(jstr) = env.new_string(&error_event.to_string()) {
                    let _ = env.call_method(
                        callback_ref.as_obj(),
                        "onEvent",
                        "(Ljava/lang/String;)V",
                        &[JValue::Object(&jstr)],
                    );
                }
            }
            let error_json = AgoraError::Config(
                "Generation task terminated unexpectedly".to_string(),
            )
            .to_json_string();
            match util::string_to_jstring(&mut env, &error_json) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// Java: nativeFetchModels(long handle, String apiKey, String baseUrl) -> String
///
/// 获取可用模型列表，返回 JSON 字符串。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_api_RustProvider_nativeFetchModels(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    api_key: JString,
    base_url: JString,
) -> jstring {
    let Some(api_key) = util::extract_jstring(&mut env, api_key, "apiKey") else {
        return std::ptr::null_mut();
    };
    let Some(base_url) = util::extract_jstring(&mut env, base_url, "baseUrl") else {
        return std::ptr::null_mut();
    };

    let http_client = AgoraHttpClient::new();
    let base_url_opt = if base_url.is_empty() { None } else { Some(base_url.as_str()) };

    let result = util::get_global_runtime().block_on(async {
        // Clone the Arc out of the lock and drop the guard BEFORE awaiting.
        let provider = {
            let providers = safe_lock(&PROVIDERS);
            match providers.get(&handle) {
                Some(p) => p.clone(),
                None => {
                    return Err(AgoraError::Config(format!("Provider not found for handle: {}", handle)));
                }
            }
        };
        provider.fetch_models(&api_key, base_url_opt, &http_client).await
    });

    match result {
        Ok(models) => {
            let response = serde_json::json!({ "models": models });
            let response_str = response.to_string();
            match util::string_to_jstring(&mut env, &response_str) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            let response = serde_json::json!({ "error": e.to_string() });
            let response_str = response.to_string();
            match util::string_to_jstring(&mut env, &response_str) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// Java: nativeDestroyProvider(long handle)
///
/// 释放 Provider 实例及其关联资源。
/// 设置取消标志，使正在运行的生成任务安全退出。
/// 安全可多次调用。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_newoether_agora_api_RustProvider_nativeDestroyProvider(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle > 0 {
        remove_provider(handle);
        log::info!("[JNI] Destroyed provider with handle {}", handle);
    }
}