// RustCrypto JNI 桥接层
//
// 对应 Kotlin `com.newoether.agora.util.RustCrypto`
// 实现 nativeGenerateKeyPair / nativeDeriveAesKey / nativeEncrypt / nativeDecrypt
//          nativeSign / nativeSha256Hex / nativeGenerateNonce

use std::collections::HashMap;
use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jlong, jstring};

use crate::crypto::{aes_gcm, ecdh, hkdf, hmac};
use crate::error::AgoraError;
use crate::jni::util;

use once_cell::sync::Lazy;

// ============================================================
// 全局密钥对存储
// ============================================================

/// 存储临时密钥对，handle → EphemeralSecret
static KEY_PAIRS: Lazy<Mutex<HashMap<i64, [u8; 32]>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

static NEXT_KEY_HANDLE: std::sync::atomic::AtomicI64 =
    std::sync::atomic::AtomicI64::new(1);

fn next_key_handle() -> i64 {
    NEXT_KEY_HANDLE.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
}

// ============================================================
// JNI 函数
// ============================================================

/// Java: nativeGenerateKeyPair() -> String
///
/// 生成 X25519 临时密钥对，返回 JSON: {"public_key": "...", "handle": N}
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeGenerateKeyPair(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let keypair = ecdh::generate_keypair();
    let public_key = ecdh::encode_public_key(&keypair.public);

    // 提取 secret 的字节表示用于存储
    // 注意：EphemeralSecret 通过 as_bytes() 获取内部字节
    let secret_bytes: [u8; 32] = {
        let bytes = keypair.secret.as_bytes();
        let mut arr = [0u8; 32];
        arr.copy_from_slice(bytes);
        arr
    };

    let handle = next_key_handle();
    KEY_PAIRS.lock().unwrap().insert(handle, secret_bytes);

    let result = serde_json::json!({
        "public_key": public_key,
        "handle": handle
    });

    let result_str = result.to_string();
    match util::string_to_jstring(&mut env, &result_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeDeriveAesKey(long handle, String serverPublicKey) -> String
///
/// 从临时密钥对和服务端公钥派生 AES-256 密钥，返回 JSON: {"key": "..."}
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeDeriveAesKey(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    server_public_key: jni::objects::JString,
) -> jstring {
    let Some(server_pub_str) = util::extract_jstring(&mut env, server_public_key, "serverPublicKey") else {
        return std::ptr::null_mut();
    };

    // 获取存储的密钥对
    let secret_bytes = {
        let pairs = KEY_PAIRS.lock().unwrap();
        match pairs.get(&handle) {
            Some(bytes) => *bytes,
            None => {
                let error = serde_json::json!({"error": "Invalid key pair handle"});
                let error_str = error.to_string();
                return match util::string_to_jstring(&mut env, &error_str) {
                    Ok(s) => s,
                    Err(_) => std::ptr::null_mut(),
                };
            }
        }
    };

    // 解码服务端公钥
    let server_pub = match ecdh::decode_public_key(&server_pub_str) {
        Ok(p) => p,
        Err(e) => {
            let error = serde_json::json!({"error": e.to_string()});
            let error_str = error.to_string();
            return match util::string_to_jstring(&mut env, &error_str) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    // 重建 secret
    let secret = match x25519_dalek::EphemeralSecret::from(secret_bytes) {
        s => s,
    };

    // 派生共享密钥
    let shared = ecdh::derive_shared_secret(secret, &server_pub);

    // 派生 AES 密钥
    let aes_key = match hkdf::derive_aes_key_default(shared.as_bytes()) {
        Ok(k) => k,
        Err(e) => {
            let error = serde_json::json!({"error": e.to_string()});
            let error_str = error.to_string();
            return match util::string_to_jstring(&mut env, &error_str) {
                Ok(s) => s,
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    // 清理已使用的密钥对
    KEY_PAIRS.lock().unwrap().remove(&handle);

    use base64::Engine;
    let key_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&aes_key);
    let result = serde_json::json!({"key": key_b64});

    let result_str = result.to_string();
    match util::string_to_jstring(&mut env, &result_str) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeEncrypt(String key, String plaintext) -> String
///
/// AES-256-GCM 加密，返回 base64url(nonce || ciphertext || tag)
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeEncrypt(
    mut env: JNIEnv,
    _class: JClass,
    key: jni::objects::JString,
    plaintext: jni::objects::JString,
) -> jstring {
    let Some(key_str) = util::extract_jstring(&mut env, key, "key") else {
        return std::ptr::null_mut();
    };
    let Some(plaintext) = util::extract_jstring(&mut env, plaintext, "plaintext") else {
        return std::ptr::null_mut();
    };

    use base64::Engine;
    let key_bytes = match base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&key_str) {
        Ok(b) => {
            if b.len() != 32 {
                util::handle_error(&mut env, AgoraError::Crypto("Invalid AES key length".to_string()));
                return std::ptr::null_mut();
            }
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&b);
            arr
        }
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Crypto(format!("Failed to decode key: {}", e)));
            return std::ptr::null_mut();
        }
    };

    let encrypted = aes_gcm::encrypt(&key_bytes, plaintext.as_bytes());
    match util::string_to_jstring(&mut env, &encrypted) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeDecrypt(String key, String ciphertext) -> String
///
/// AES-256-GCM 解密，返回 UTF-8 明文
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeDecrypt(
    mut env: JNIEnv,
    _class: JClass,
    key: jni::objects::JString,
    ciphertext: jni::objects::JString,
) -> jstring {
    let Some(key_str) = util::extract_jstring(&mut env, key, "key") else {
        return std::ptr::null_mut();
    };
    let Some(ciphertext) = util::extract_jstring(&mut env, ciphertext, "ciphertext") else {
        return std::ptr::null_mut();
    };

    use base64::Engine;
    let key_bytes = match base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&key_str) {
        Ok(b) => {
            if b.len() != 32 {
                util::handle_error(&mut env, AgoraError::Crypto("Invalid AES key length".to_string()));
                return std::ptr::null_mut();
            }
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&b);
            arr
        }
        Err(e) => {
            util::handle_error(&mut env, AgoraError::Crypto(format!("Failed to decode key: {}", e)));
            return std::ptr::null_mut();
        }
    };

    match aes_gcm::decrypt(&key_bytes, &ciphertext) {
        Ok(plaintext) => {
            match String::from_utf8(plaintext) {
                Ok(s) => match util::string_to_jstring(&mut env, &s) {
                    Ok(s) => s,
                    Err(_) => std::ptr::null_mut(),
                },
                Err(e) => {
                    util::handle_error(&mut env, AgoraError::Crypto(format!("Invalid UTF-8: {}", e)));
                    std::ptr::null_mut()
                }
            }
        }
        Err(e) => {
            util::handle_error(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

/// Java: nativeSign(String apiKey, long timestamp, String method, String path,
///                  String bodySha256, String nonce, String clientPubKey) -> String
///
/// HMAC-SHA256 签名，返回 hex 编码
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeSign(
    mut env: JNIEnv,
    _class: JClass,
    api_key: jni::objects::JString,
    timestamp: jlong,
    method: jni::objects::JString,
    path: jni::objects::JString,
    body_sha256: jni::objects::JString,
    nonce: jni::objects::JString,
    client_pub_key: jni::objects::JString,
) -> jstring {
    let Some(api_key) = util::extract_jstring(&mut env, api_key, "apiKey") else {
        return std::ptr::null_mut();
    };
    let Some(method) = util::extract_jstring(&mut env, method, "method") else {
        return std::ptr::null_mut();
    };
    let Some(path) = util::extract_jstring(&mut env, path, "path") else {
        return std::ptr::null_mut();
    };
    let Some(body_sha256) = util::extract_jstring(&mut env, body_sha256, "bodySha256") else {
        return std::ptr::null_mut();
    };
    let Some(nonce) = util::extract_jstring(&mut env, nonce, "nonce") else {
        return std::ptr::null_mut();
    };
    let Some(client_pub_key) = util::extract_jstring(&mut env, client_pub_key, "clientPubKey") else {
        return std::ptr::null_mut();
    };

    let signature = hmac::sign(
        &api_key,
        timestamp,
        &method,
        &path,
        &body_sha256,
        &nonce,
        &client_pub_key,
    );

    match util::string_to_jstring(&mut env, &signature) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeSha256Hex(String data) -> String
///
/// SHA-256 哈希，返回 hex 编码
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeSha256Hex(
    mut env: JNIEnv,
    _class: JClass,
    data: jni::objects::JString,
) -> jstring {
    let Some(data) = util::extract_jstring(&mut env, data, "data") else {
        return std::ptr::null_mut();
    };

    let hash = hmac::sha256_hex(data.as_bytes());
    match util::string_to_jstring(&mut env, &hash) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: nativeGenerateNonce() -> String
///
/// 生成随机 nonce（base64url 编码，12 字节）
#[no_mangle]
pub extern "system" fn Java_com_newoether_agora_util_RustCrypto_nativeGenerateNonce(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let nonce = hmac::generate_nonce();
    match util::string_to_jstring(&mut env, &nonce) {
        Ok(s) => s,
        Err(_) => std::ptr::null_mut(),
    }
}