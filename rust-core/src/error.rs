use serde::{Deserialize, Serialize};
use thiserror::Error;

/// 应用级统一错误类型
#[derive(Error, Debug, Clone, Serialize, Deserialize)]
pub enum AgoraError {
    #[error("API error [{code}]: {message}")]
    Api {
        code: String,
        message: String,
        error_type: Option<String>,
    },

    #[error("Network error (HTTP {status_code}): {message}")]
    Network { status_code: i32, message: String },

    #[error("Request timeout")]
    Timeout,

    #[error("Request format error: {provider} - {violations:?}")]
    RequestFormat {
        provider: String,
        violations: Vec<String>,
    },

    #[error("Stream error: {0}")]
    Stream(String),

    #[error("Parse error: {0}")]
    Parse(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Crypto error: {0}")]
    Crypto(String),

    #[error("Shell error: {0}")]
    Shell(String),

    #[error("Embedding error: {0}")]
    Embedding(String),

    #[error("Storage error: {0}")]
    Storage(String),

    #[error("JNI error: {0}")]
    Jni(String),

    #[error("IO error: {0}")]
    Io(String),

    #[error("Unknown error: {0}")]
    Unknown(String),
}

pub type AgoraResult<T> = Result<T, AgoraError>;

impl From<reqwest::Error> for AgoraError {
    fn from(e: reqwest::Error) -> Self {
        if e.is_timeout() {
            AgoraError::Timeout
        } else if e.is_connect() {
            AgoraError::Network {
                status_code: 0,
                message: e.to_string(),
            }
        } else {
            AgoraError::Network {
                status_code: 0,
                message: e.to_string(),
            }
        }
    }
}

impl From<serde_json::Error> for AgoraError {
    fn from(e: serde_json::Error) -> Self {
        AgoraError::Parse(e.to_string())
    }
}

impl From<std::io::Error> for AgoraError {
    fn from(e: std::io::Error) -> Self {
        AgoraError::Io(e.to_string())
    }
}

impl From<base64::DecodeError> for AgoraError {
    fn from(e: base64::DecodeError) -> Self {
        AgoraError::Crypto(e.to_string())
    }
}

impl From<hkdf::InvalidLength> for AgoraError {
    fn from(e: hkdf::InvalidLength) -> Self {
        AgoraError::Crypto(e.to_string())
    }
}

impl From<aes_gcm::Error> for AgoraError {
    fn from(e: aes_gcm::Error) -> Self {
        AgoraError::Crypto(e.to_string())
    }
}

impl From<zip::result::ZipError> for AgoraError {
    fn from(e: zip::result::ZipError) -> Self {
        AgoraError::Storage(e.to_string())
    }
}

impl AgoraError {
    /// 转换为 JNI 可传递的 JSON 字符串
    pub fn to_json_string(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| {
            // 安全的回退：手动构建 JSON，对 message 进行转义
            let msg = self.to_string().replace('\\', "\\\\").replace('"', "\\\"");
            format!("{{\"type\":\"Unknown\",\"message\":\"{}\"}}", msg)
        })
    }

    /// 判断是否为可重试的瞬态错误
    pub fn is_retryable(&self) -> bool {
        match self {
            AgoraError::Network { status_code, .. } => {
                *status_code == 429 || *status_code == 502 || *status_code == 503 || *status_code == 504
            }
            AgoraError::Timeout => true,
            _ => false,
        }
    }

    /// 获取 HTTP 状态码（如果有）
    pub fn status_code(&self) -> Option<i32> {
        match self {
            AgoraError::Network { status_code, .. } => Some(*status_code),
            _ => None,
        }
    }
}
