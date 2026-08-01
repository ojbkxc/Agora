use std::collections::HashMap;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use x25519_dalek::PublicKey;

use crate::error::{AgoraError, AgoraResult};

use super::aes_gcm;
use super::ecdh::{self, EphemeralKeyPair};
use super::hkdf;
use super::hmac;

/// Conch 加密会话（对应 Kotlin ShellClient 的加密状态）
pub struct ConchSession {
    aes_key: [u8; 32],
    keypair: EphemeralKeyPair,
    server_public_key: Option<PublicKey>,
}

impl ConchSession {
    /// 创建新会话（生成临时密钥对，AES key 初始化为零）
    pub fn new() -> Self {
        Self {
            aes_key: [0u8; 32],
            keypair: ecdh::generate_keypair(),
            server_public_key: None,
        }
    }

    /// 设置服务器公钥（base64url 编码）。
    /// 实际的 AES 密钥派生在 `prepare_request` 中进行，
    /// 确保每个请求使用独立的临时密钥对（ECDHE）。
    pub fn set_server_public_key(&mut self, encoded: &str) -> AgoraResult<()> {
        let server_pub = ecdh::decode_public_key(encoded)?;
        self.server_public_key = Some(server_pub);
        Ok(())
    }

    /// 生成客户端公钥（base64url 编码）
    pub fn generate_client_public_key(&self) -> String {
        ecdh::encode_public_key(&self.keypair.public)
    }

    /// 获取当前 AES 密钥引用
    pub fn aes_key(&self) -> &[u8; 32] {
        &self.aes_key
    }

    /// 获取服务器公钥引用
    pub fn server_public_key(&self) -> Option<&PublicKey> {
        self.server_public_key.as_ref()
    }

    /// 准备加密请求，返回 (encrypted_body, headers)
    ///
    /// 对应 Kotlin ShellClient.prepareRequest / encryptedPost
    pub fn prepare_request(
        &mut self,
        api_key: &str,
        method: &str,
        path: &str,
        body: &str,
    ) -> AgoraResult<(String, HashMap<String, String>)> {
        let server_pub = self
            .server_public_key
            .as_ref()
            .ok_or_else(|| AgoraError::Crypto("Server public key not set".to_string()))?;

        // 生成新的临时密钥对（每个请求独立的密钥对，ECDHE）
        let new_keypair = ecdh::generate_keypair();
        let client_pub_key = ecdh::encode_public_key(&new_keypair.public);

        // 派生共享密钥
        let shared = ecdh::derive_shared_secret(&new_keypair.secret, server_pub);
        self.aes_key = hkdf::derive_aes_key_default(shared.as_bytes())?;

        // 加密请求体
        let encrypted_body = aes_gcm::encrypt(&self.aes_key, body.as_bytes());

        // 计算签名
        let body_sha256 = hmac::sha256_hex(encrypted_body.as_bytes());
        let timestamp = chrono::Utc::now().timestamp();
        let nonce = hmac::generate_nonce();
        let signature = hmac::sign(
            api_key,
            timestamp,
            method,
            path,
            &body_sha256,
            &nonce,
            &client_pub_key,
        );

        let mut headers = HashMap::new();
        headers.insert("Content-Type".to_string(), "application/octet-stream".to_string());
        headers.insert("X-Timestamp".to_string(), timestamp.to_string());
        headers.insert("X-Signature".to_string(), signature);
        headers.insert("X-Nonce".to_string(), nonce);
        headers.insert("X-Encryption".to_string(), "v1".to_string());
        headers.insert("X-Client-Public-Key".to_string(), client_pub_key);

        Ok((encrypted_body, headers))
    }

    /// 解密响应
    pub fn decrypt_response(&self, encrypted: &str) -> AgoraResult<String> {
        let plaintext = aes_gcm::decrypt(&self.aes_key, encrypted)?;
        String::from_utf8(plaintext).map_err(|e| AgoraError::Crypto(format!("Invalid UTF-8: {}", e)))
    }

    /// 验证服务器公钥签名
    ///
    /// 对应 Kotlin ShellClient.verifyPublicKeySignature
    pub fn verify_public_key_signature(
        api_key: &str,
        pub_key: &str,
        nonce: &str,
        signature: &str,
    ) -> bool {
        let message = format!("{}|{}", nonce, pub_key);
        let expected = hmac::hmac_sign(api_key.as_bytes(), message.as_bytes());
        let expected_hex = hex::encode(&expected);
        hmac::constant_time_eq(expected_hex.as_bytes(), signature.as_bytes())
    }
}

impl Default for ConchSession {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_verify_public_key_signature() {
        let api_key = "test-api-key";
        let pub_key = "some-public-key";
        let nonce = "test-nonce";

        // 计算期望签名
        let message = format!("{}|{}", nonce, pub_key);
        let expected_bytes = hmac::hmac_sign(api_key.as_bytes(), message.as_bytes());
        let expected_sig = hex::encode(&expected_bytes);

        assert!(ConchSession::verify_public_key_signature(
            api_key, pub_key, nonce, &expected_sig
        ));

        // 错误签名
        assert!(!ConchSession::verify_public_key_signature(
            api_key, pub_key, nonce, "wrong-sig"
        ));
    }
}
