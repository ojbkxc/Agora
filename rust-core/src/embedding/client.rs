use crate::api::http_client::AgoraHttpClient;
use crate::error::{AgoraError, AgoraResult};

/// 单文本 embedding
pub async fn compute_embedding(
    text: &str,
    api_key: &str,
    model: &str,
    base_url: &str,
    client: &AgoraHttpClient,
) -> AgoraResult<Vec<f32>> {
    let url = format!("{}/embeddings", base_url);
    let body = serde_json::json!({
        "input": text,
        "model": model,
    })
    .to_string();

    let mut headers = std::collections::HashMap::new();
    headers.insert("Content-Type".to_string(), "application/json".to_string());
    if !api_key.is_empty() {
        headers.insert("Authorization".to_string(), format!("Bearer {}", api_key));
    }

    let resp = client
        .post_json_value(&url, &body, headers)
        .await?;

    let data = resp
        .get("data")
        .and_then(|v| v.as_array())
        .ok_or_else(|| AgoraError::Embedding("Missing 'data' in embedding response".to_string()))?;

    let first = data
        .first()
        .ok_or_else(|| AgoraError::Embedding("Empty 'data' array in embedding response".to_string()))?;

    let embedding = first
        .get("embedding")
        .and_then(|v| v.as_array())
        .ok_or_else(|| AgoraError::Embedding("Missing 'embedding' in response".to_string()))?;

    Ok(embedding
        .iter()
        .map(|v| v.as_f64().unwrap_or(0.0) as f32)
        .collect())
}

/// 批量 embedding
pub async fn compute_embeddings(
    texts: &[String],
    api_key: &str,
    model: &str,
    base_url: &str,
    client: &AgoraHttpClient,
) -> AgoraResult<Vec<Option<Vec<f32>>>> {
    if texts.is_empty() {
        return Ok(Vec::new());
    }

    let url = format!("{}/embeddings", base_url);
    let body = serde_json::json!({
        "input": texts,
        "model": model,
    })
    .to_string();

    let mut headers = std::collections::HashMap::new();
    headers.insert("Content-Type".to_string(), "application/json".to_string());
    if !api_key.is_empty() {
        headers.insert("Authorization".to_string(), format!("Bearer {}", api_key));
    }

    let resp = match client.post_json_value(&url, &body, headers).await {
        Ok(v) => v,
        Err(_) => return Ok(texts.iter().map(|_| None).collect()),
    };

    let data = match resp.get("data").and_then(|v| v.as_array()) {
        Some(arr) => arr,
        None => return Ok(texts.iter().map(|_| None).collect()),
    };

    Ok(data
        .iter()
        .map(|item| {
            item.get("embedding")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().map(|v| v.as_f64().unwrap_or(0.0) as f32).collect())
        })
        .collect())
}

/// 使用默认模型和 base_url 的便捷函数
pub async fn compute_embedding_default(
    text: &str,
    api_key: &str,
    client: &AgoraHttpClient,
) -> AgoraResult<Vec<f32>> {
    compute_embedding(
        text,
        api_key,
        "text-embedding-3-small",
        "https://api.openai.com/v1",
        client,
    )
    .await
}
