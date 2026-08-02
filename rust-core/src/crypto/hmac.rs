use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

/// 生成 HMAC-SHA256 签名（hex 编码）
///
/// 签名消息格式："{timestamp}|{method}|{path}|{body_sha256}|{nonce}|{client_pub_key}"
pub fn sign(
    api_key: &str,
    timestamp: i64,
    method: &str,
    path: &str,
    body_sha256: &str,
    nonce: &str,
    client_pub_key: &str,
) -> String {
    let message = format!(
        "{}|{}|{}|{}|{}|{}",
        timestamp, method, path, body_sha256, nonce, client_pub_key
    );

    let mut mac =
        HmacSha256::new_from_slice(api_key.as_bytes()).expect("HMAC accepts any key length");
    mac.update(message.as_bytes());
    let result = mac.finalize();
    hex::encode(result.into_bytes())
}

/// SHA-256 hex digest
pub fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

/// 生成 16 字节随机 nonce，返回 base64url 编码
///
/// 使用 16 字节（128 位）以匹配 Kotlin 端 RustCrypto.nativeGenerateNonce 的预期。
pub fn generate_nonce() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

/// HMAC-SHA256 用于公钥签名验证
pub fn hmac_sign(key: &[u8], message: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(message);
    mac.finalize().into_bytes().to_vec()
}

/// 恒定时间比较两个字节切片
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sha256_hex() {
        let hash = sha256_hex(b"hello");
        assert_eq!(hash.len(), 64);
        // SHA-256 of "hello"
        assert_eq!(
            hash,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
    }

    #[test]
    fn test_sign_deterministic() {
        let sig1 = sign("key1", 12345, "POST", "/api", "abc", "nonce", "pubkey");
        let sig2 = sign("key1", 12345, "POST", "/api", "abc", "nonce", "pubkey");
        assert_eq!(sig1, sig2);
    }

    #[test]
    fn test_sign_diff_key() {
        let sig1 = sign("key1", 12345, "POST", "/api", "abc", "nonce", "pubkey");
        let sig2 = sign("key2", 12345, "POST", "/api", "abc", "nonce", "pubkey");
        assert_ne!(sig1, sig2);
    }

    #[test]
    fn test_generate_nonce_unique() {
        let n1 = generate_nonce();
        let n2 = generate_nonce();
        assert_ne!(n1, n2);
    }

    #[test]
    fn test_constant_time_eq() {
        assert!(constant_time_eq(b"hello", b"hello"));
        assert!(!constant_time_eq(b"hello", b"world"));
        assert!(!constant_time_eq(b"hello", b"hell"));
    }
}
