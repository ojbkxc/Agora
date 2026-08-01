/// 余弦相似度计算
pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() || a.is_empty() {
        return 0.0;
    }

    let mut dot_product = 0.0f32;
    let mut norm_a = 0.0f32;
    let mut norm_b = 0.0f32;

    for (x, y) in a.iter().zip(b.iter()) {
        dot_product += x * y;
        norm_a += x * x;
        norm_b += y * y;
    }

    let denominator = norm_a.sqrt() * norm_b.sqrt();
    if denominator == 0.0 {
        return 0.0;
    }

    dot_product / denominator
}

/// 查找最相似的向量
///
/// 返回 (id, similarity) 列表，按相似度降序排列
pub fn find_similar(
    query: &[f32],
    embeddings: &[(i64, Vec<f32>)],
    threshold: f32,
    limit: usize,
) -> Vec<(i64, f32)> {
    let mut results: Vec<(i64, f32)> = embeddings
        .iter()
        .map(|(id, emb)| (*id, cosine_similarity(query, emb)))
        .filter(|(_, sim)| *sim >= threshold)
        .collect();

    results.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    results.truncate(limit);
    results
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cosine_similarity_identical() {
        let a = vec![1.0, 0.0, 0.0];
        let b = vec![1.0, 0.0, 0.0];
        let sim = cosine_similarity(&a, &b);
        assert!((sim - 1.0).abs() < 1e-6);
    }

    #[test]
    fn test_cosine_similarity_orthogonal() {
        let a = vec![1.0, 0.0, 0.0];
        let b = vec![0.0, 1.0, 0.0];
        let sim = cosine_similarity(&a, &b);
        assert!((sim - 0.0).abs() < 1e-6);
    }

    #[test]
    fn test_cosine_similarity_opposite() {
        let a = vec![1.0, 0.0, 0.0];
        let b = vec![-1.0, 0.0, 0.0];
        let sim = cosine_similarity(&a, &b);
        assert!((sim - (-1.0)).abs() < 1e-6);
    }

    #[test]
    fn test_cosine_similarity_partial() {
        let a = vec![1.0, 1.0, 0.0];
        let b = vec![1.0, 0.0, 0.0];
        let sim = cosine_similarity(&a, &b);
        // cos(45°) ≈ 0.7071
        assert!((sim - 0.70710677).abs() < 1e-5);
    }

    #[test]
    fn test_cosine_similarity_empty() {
        let a: Vec<f32> = vec![];
        let b: Vec<f32> = vec![];
        assert_eq!(cosine_similarity(&a, &b), 0.0);
    }

    #[test]
    fn test_cosine_similarity_different_lengths() {
        let a = vec![1.0, 2.0];
        let b = vec![1.0, 2.0, 3.0];
        assert_eq!(cosine_similarity(&a, &b), 0.0);
    }

    #[test]
    fn test_cosine_similarity_zero_vector() {
        let a = vec![0.0, 0.0, 0.0];
        let b = vec![1.0, 2.0, 3.0];
        assert_eq!(cosine_similarity(&a, &b), 0.0);
    }

    #[test]
    fn test_find_similar_basic() {
        let query = vec![1.0, 0.0, 0.0];
        let embeddings = vec![
            (1, vec![1.0, 0.0, 0.0]),   // sim = 1.0
            (2, vec![0.0, 1.0, 0.0]),   // sim = 0.0
            (3, vec![0.7, 0.7, 0.0]),   // sim ≈ 0.707
            (4, vec![-1.0, 0.0, 0.0]),  // sim = -1.0
        ];

        let results = find_similar(&query, &embeddings, 0.5, 10);
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].0, 1); // highest similarity
        assert_eq!(results[1].0, 3);
    }

    #[test]
    fn test_find_similar_limit() {
        let query = vec![1.0, 0.0, 0.0];
        let embeddings = vec![
            (1, vec![1.0, 0.0, 0.0]),
            (2, vec![0.9, 0.1, 0.0]),
            (3, vec![0.8, 0.2, 0.0]),
            (4, vec![0.7, 0.3, 0.0]),
        ];

        let results = find_similar(&query, &embeddings, 0.0, 2);
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].0, 1);
        assert_eq!(results[1].0, 2);
    }

    #[test]
    fn test_find_similar_threshold() {
        let query = vec![1.0, 0.0];
        let embeddings = vec![
            (1, vec![1.0, 0.0]),  // sim = 1.0
            (2, vec![0.5, 0.5]), // sim ≈ 0.707
            (3, vec![0.0, 1.0]), // sim = 0.0
        ];

        let results = find_similar(&query, &embeddings, 0.7, 10);
        assert_eq!(results.len(), 2);
    }

    #[test]
    fn test_find_similar_empty() {
        let query = vec![1.0, 0.0];
        let embeddings: Vec<(i64, Vec<f32>)> = vec![];
        let results = find_similar(&query, &embeddings, 0.0, 10);
        assert!(results.is_empty());
    }
}
