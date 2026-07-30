from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    ai_service_host: str = "0.0.0.0"
    ai_service_port: int = 8015
    ai_service_api_key: str = ""

    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_api_key: str = ""
    qwen_model: str = "qwen-plus"
    qwen_vl_endpoint: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    qwen_vl_api_key: str = ""
    qwen_vl_model: str = "qwen-vl-plus"
    qwen_vl_timeout_seconds: int = 120
    qwen_vl_max_image_bytes: int = 10 * 1024 * 1024
    qwen_vl_max_tokens: int = 8192
    qwen_embedding_model: str = "text-embedding-v4"
    qwen_embedding_dimensions: int = 1024
    qwen_embedding_batch_size: int = 10
    qwen_rerank_base_url: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    qwen_rerank_model: str = "qwen3-rerank"
    qwen_rerank_api_style: str = "LEGACY"
    qwen_rerank_instruct: str = "Given a web search query, retrieve relevant passages that answer the query."
    qwen_timeout_seconds: int = 120

    embedding_provider: str = "QWEN"
    rerank_provider: str = "QWEN"
    rag_provider: str = "LOCAL"
    rag_data_dir: str = "data/rag"
    rag_chunk_size: int = 800
    rag_chunk_overlap: int = 120
    rag_rerank_top_k: int = 20

    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_token: str = ""
    milvus_collection: str = "smart_worksite_chunks"

    pgvector_dsn: str = ""
    pgvector_table: str = "smart_worksite_chunks"

    # 政策资讯采集。调大 policy_crawl_max_articles 需同步调大 Java 侧
    # AI_PYTHON_READ_TIMEOUT_MS，否则单次采集会读超时：
    # 预计耗时 ≈ max_articles × (delay_seconds + 单页下载耗时)
    policy_crawl_respect_robots: bool = True
    policy_crawl_delay_seconds: float = 1.5
    policy_crawl_max_articles: int = 30
    policy_crawl_timeout_seconds: int = 30
    policy_crawl_min_article_chars: int = 200
    policy_crawl_max_detail_links: int = 20

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
