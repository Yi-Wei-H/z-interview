-- pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 向量表 vector_store
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024)
);

-- HNSW 余弦相似度索引
CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
