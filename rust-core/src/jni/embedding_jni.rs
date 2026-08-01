// RustEmbeddingClient JNI 桥接层
//
// 对应 Kotlin `com.newoether.agora.api.RustEmbeddingClient`
// 实现 nativeComputeEmbedding / nativeComputeEmbeddings

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jstring;

use crate::api::http_client::AgoraHttpClient;
use crate::embedding;
use crate::error::AgoraError;
use crate::jni::util;

// ============================================================
// JNI 函数
// ============================================================

/// Java: nativeComputeEmbedding(String text, String apiKey, String model, String baseUrl) -> String
///
/// 计算单个文本的 embedding 向量，返回 JSON: {"embedding": [0.1, 0.2, ...]}
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_api_RustEmbeddingClient_nativeComputeEmbedding(
    mut env: JNIEnv,
    _class: JClass,
    text: jni::objects::JString,
    api_key: jni::objects::JString,
    model: jni::objects::JString,
    base_url: jni::objects::JString,
) -> jstring {
    let Some(text) = util::extract_jstring(&mut env, text, "text") else {
        return std::ptr::null_mut();
    };
    let Some(api_key) = util::extract_jstring(&mut env, api_key, "apiKey") else {
        return std::ptr::null_mut();
    };
    let Some(model) = util::extract_jstring(&mut env, model, "model") else {
        return std::ptr::null_mut();
    };
    let Some(base_url) = util::extract_jstring(&mut env, base_url, "baseUrl") else {
        return std::ptr::null_mut();
    };

    let client = AgoraHttpClient::new();

    let result = util::get_global_runtime().block_on(async {
        embedding::client::compute_embedding(&text, &api_key, &model, &base_url, &client).await
    });

    let response_str = match result {
        Ok(embedding) => {
            serde_json::json!({"embedding": embedding}).to_string()
        }
        Err(e) => {
            serde_json::json!({"error": e.to_string()}).to_string()
        }
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeComputeEmbeddings(String texts, String apiKey, String model, String baseUrl) -> String
///
/// 计算批量文本的 embedding 向量，返回 JSON: {"embeddings": [[0.1, ...], [0.2, ...]]}
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_api_RustEmbeddingClient_nativeComputeEmbeddings(
    mut env: JNIEnv,
    _class: JClass,
    texts: jni::objects::JString,
    api_key: jni::objects::JString,
    model: jni::objects::JString,
    base_url: jni::objects::JString,
) -> jstring {
    let Some(texts_json) = util::extract_jstring(&mut env, texts, "texts") else {
        return std::ptr::null_mut();
    };
    let Some(api_key) = util::extract_jstring(&mut env, api_key, "apiKey") else {
        return std::ptr::null_mut();
    };
    let Some(model) = util::extract_jstring(&mut env, model, "model") else {
        return std::ptr::null_mut();
    };
    let Some(base_url) = util::extract_jstring(&mut env, base_url, "baseUrl") else {
        return std::ptr::null_mut();
    };

    // 解析文本数组
    let text_list: Vec<String> = match serde_json::from_str(&texts_json) {
        Ok(t) => t,
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Parse(format!("Failed to parse texts: {}", e)));
            return std::ptr::null_mut();
        }
    };

    let client = AgoraHttpClient::new();

    let result = util::get_global_runtime().block_on(async {
        embedding::client::compute_embeddings(&text_list, &api_key, &model, &base_url, &client).await
    });

    let response_str = match result {
        Ok(embeddings) => {
            serde_json::json!({"embeddings": embeddings}).to_string()
        }
        Err(e) => {
            serde_json::json!({"error": e.to_string()}).to_string()
        }
    };

    match util::string_to_jstring(&mut env, &response_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}