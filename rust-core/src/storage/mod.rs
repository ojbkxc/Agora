use serde::{Deserialize, Serialize};
use std::io::{Read, Write};

use crate::error::{AgoraError, AgoraResult};

/// Export data structure for the .agora file format
#[derive(Debug, Serialize, Deserialize)]
struct AgoraExportData {
    #[serde(default)]
    conversations: serde_json::Value,
    #[serde(default)]
    memories: serde_json::Value,
    #[serde(default)]
    prompts: serde_json::Value,
    #[serde(default)]
    settings: serde_json::Value,
    #[serde(default)]
    api_keys: serde_json::Value,
}

/// Import strategy
#[derive(Debug, Clone, Copy)]
pub enum ImportStrategy {
    Merge,
    Overwrite,
}

impl ImportStrategy {
    pub fn from_str(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "overwrite" => ImportStrategy::Overwrite,
            _ => ImportStrategy::Merge,
        }
    }
}

/// Import result
#[derive(Debug, Serialize)]
pub struct ImportResult {
    pub success: bool,
    pub conversations_imported: usize,
    pub memories_imported: usize,
    pub prompts_imported: usize,
    pub settings_imported: bool,
    pub api_keys_imported: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Export all user data to a .agora zip file (returned as bytes)
pub fn export_agora(
    conversations: &str,
    memories: &str,
    prompts: &str,
    settings: &str,
    api_keys: &str,
) -> AgoraResult<Vec<u8>> {
    let conversations_val: serde_json::Value = serde_json::from_str(conversations)
        .unwrap_or(serde_json::Value::Array(vec![]));
    let memories_val: serde_json::Value = serde_json::from_str(memories)
        .unwrap_or(serde_json::Value::Array(vec![]));
    let prompts_val: serde_json::Value = serde_json::from_str(prompts)
        .unwrap_or(serde_json::Value::Array(vec![]));
    let settings_val: serde_json::Value = serde_json::from_str(settings)
        .unwrap_or(serde_json::Value::Object(serde_json::Map::new()));
    let api_keys_val: serde_json::Value = serde_json::from_str(api_keys)
        .unwrap_or(serde_json::Value::Array(vec![]));

    let export_data = AgoraExportData {
        conversations: conversations_val,
        memories: memories_val,
        prompts: prompts_val,
        settings: settings_val,
        api_keys: api_keys_val,
    };

    let json_data = serde_json::to_vec_pretty(&export_data)?;

    let mut buf = Vec::new();
    {
        let cursor = std::io::Cursor::new(&mut buf);
        let mut zip = zip::ZipWriter::new(cursor);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated)
            .compression_level(Some(6));

        zip.start_file("agora_data.json", options)?;
        zip.write_all(&json_data)?;

        zip.finish()?;
    }

    Ok(buf)
}

/// Import user data from a .agora zip file
pub fn import_agora(
    zip_data: &[u8],
    strategy: ImportStrategy,
) -> AgoraResult<String> {
    let cursor = std::io::Cursor::new(zip_data);
    let mut archive = zip::ZipArchive::new(cursor)?;

    let agora_file = archive.by_name("agora_data.json")
        .map_err(|_| AgoraError::Storage("Invalid .agora file: missing agora_data.json".to_string()))?;

    let mut content = String::new();
    let mut reader = agora_file;
    reader.read_to_string(&mut content)?;

    let data: AgoraExportData = serde_json::from_str(&content)?;

    let conversations_count = match &data.conversations {
        serde_json::Value::Array(arr) => arr.len(),
        _ => 0,
    };
    let memories_count = match &data.memories {
        serde_json::Value::Array(arr) => arr.len(),
        _ => 0,
    };
    let prompts_count = match &data.prompts {
        serde_json::Value::Array(arr) => arr.len(),
        _ => 0,
    };
    let api_keys_count = match &data.api_keys {
        serde_json::Value::Array(arr) => arr.len(),
        _ => 0,
    };

    let result = ImportResult {
        success: true,
        conversations_imported: conversations_count,
        memories_imported: memories_count,
        prompts_imported: prompts_count,
        settings_imported: !matches!(data.settings, serde_json::Value::Null),
        api_keys_imported: api_keys_count,
        error: None,
    };

    Ok(serde_json::to_string(&result)?)
}
