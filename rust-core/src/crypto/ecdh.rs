use crate::error::{AgoraError, AgoraResult};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use x25519_dalek::{EphemeralSecret, PublicKey, SharedSecret};

/// 临时 X25519 密钥对
pub struct EphemeralKeyPair {
    pub secret: EphemeralSecret,
    pub public: PublicKey,
}

/// 生成临时 X25519 密钥对
pub fn generate_keypair() -> EphemeralKeyPair {
    let secret = EphemeralSecret::random_from_rng(rand::thread_rng());
    let public = PublicKey::from(&secret);
    EphemeralKeyPair { secret, public }
}

/// 将公钥编码为 base64url 字符串（原始 32 字节）
pub fn encode_public_key(public: &PublicKey) -> String {
    URL_SAFE_NO_PAD.encode(public.as_bytes())
}

/// 从 base64url 字符串解码公钥
pub fn decode_public_key(encoded: &str) -> AgoraResult<PublicKey> {
    let bytes = URL_SAFE_NO_PAD.decode(encoded)?;
    if bytes.len() != 32 {
        return Err(AgoraError::Crypto(format!(
            "Invalid X25519 public key length: {} (expected 32)",
            bytes.len()
        )));
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    Ok(PublicKey::from(arr))
}

/// 派生共享密钥
pub fn derive_shared_secret(secret: EphemeralSecret, public: &PublicKey) -> SharedSecret {
    secret.diffie_hellman(public)
}
