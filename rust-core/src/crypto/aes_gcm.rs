use crate::error::{AgoraError, AgoraResult};
use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;

pub const NONCE_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16; // 128 bits

/// AES-256-GCM 加密
///
/// 返回格式：base64url(nonce || ciphertext || tag)
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> AgoraResult<String> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| AgoraError::Crypto("Invalid AES key length".to_string()))?;

    let mut nonce_bytes = [0u8; NONCE_SIZE];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|_| AgoraError::Crypto("AES-GCM encryption failed".to_string()))?;

    // nonce || ciphertext (ciphertext already includes GCM tag)
    let mut combined = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    combined.extend_from_slice(&nonce_bytes);
    combined.extend_from_slice(&ciphertext);

    Ok(URL_SAFE_NO_PAD.encode(&combined))
}

/// AES-256-GCM 解密
///
/// 输入格式：base64url(nonce || ciphertext || tag)
pub fn decrypt(key: &[u8; 32], encoded: &str) -> AgoraResult<Vec<u8>> {
    let raw = URL_SAFE_NO_PAD.decode(encoded)?;

    if raw.len() < NONCE_SIZE + TAG_SIZE + 1 {
        return Err(AgoraError::Crypto(format!(
            "Ciphertext too short: {} bytes",
            raw.len()
        )));
    }

    let (nonce_bytes, ciphertext) = raw.split_at(NONCE_SIZE);
    let nonce = Nonce::from_slice(nonce_bytes);

    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| AgoraError::Crypto("Invalid AES key length".to_string()))?;

    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| AgoraError::Crypto("AES-GCM decryption failed".to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = [42u8; 32];
        let plaintext = b"Hello, Conch!";
        let encoded = encrypt(&key, plaintext).unwrap();
        let decrypted = decrypt(&key, &encoded).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_decrypt_wrong_key() {
        let key1 = [1u8; 32];
        let key2 = [2u8; 32];
        let encoded = encrypt(&key1, b"secret").unwrap();
        assert!(decrypt(&key2, &encoded).is_err());
    }

    #[test]
    fn test_decrypt_tampered() {
        let key = [0u8; 32];
        let encoded = encrypt(&key, b"data").unwrap();
        let mut raw = URL_SAFE_NO_PAD.decode(&encoded).unwrap();
        let last = raw.len() - 1;
        raw[last] ^= 0xFF;
        let tampered = URL_SAFE_NO_PAD.encode(&raw);
        assert!(decrypt(&key, &tampered).is_err());
    }
}
