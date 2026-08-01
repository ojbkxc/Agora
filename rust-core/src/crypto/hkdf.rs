use crate::error::AgoraResult;
use hkdf::Hkdf;
use sha2::Sha256;

const HKDF_INFO: &[u8] = b"conch-agora-v1";

/// 从共享密钥派生 AES-256 密钥（HKDF-SHA256 expand）
pub fn derive_aes_key(shared_secret: &[u8], info: &[u8]) -> AgoraResult<[u8; 32]> {
    let hk = Hkdf::<Sha256>::new(None, shared_secret);
    let mut okm = [0u8; 32];
    hk.expand(info, &mut okm)
        .map_err(|e| crate::error::AgoraError::Crypto(e.to_string()))?;
    Ok(okm)
}

/// 使用默认 info（"conch-agora-v1"）派生 AES 密钥
pub fn derive_aes_key_default(shared_secret: &[u8]) -> AgoraResult<[u8; 32]> {
    derive_aes_key(shared_secret, HKDF_INFO)
}
