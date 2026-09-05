-- 知识库检索迁移：内存关键词打分 → Postgres pgvector 余弦检索。
-- 前置：ea-postgres 容器已切换 pgvector/pgvector:pg16 镜像（捆绑 PostgreSQL 16.15，≥ 既有 16.14 数据卷可直接复用）；
--       应用连接用户为容器超级用户，迁移可直接 CREATE EXTENSION。
-- embedding 为特征哈希（hashing trick）向量：词元（CJK 二元组 / latin 词）按标题 x3、标签 x2、内容 x1 加权，
--       双哈希落到 256 维带符号累加后 L2 归一；维度须与 KnowledgeBaseService.EMBEDDING_DIM 一致。
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE knowledge ADD COLUMN embedding vector(256);

-- 余弦相似度 HNSW 索引；多租户/enabled 过滤在查询条件（表小，精确扫描无碍，索引为声明式扩展）。
CREATE INDEX idx_knowledge_embedding_hnsw ON knowledge USING hnsw (embedding vector_cosine_ops);