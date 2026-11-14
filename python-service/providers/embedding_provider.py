from __future__ import annotations

import os
from typing import Protocol

from langchain_huggingface import HuggingFaceEmbeddings


class EmbeddingProviderError(RuntimeError):
    pass

#文本 → 向量
class EmbeddingProvider(Protocol):
    model_name: str
    dimension: int | None
    normalized: bool #归一化：归一化之后，内积 = 余弦相似度

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class HuggingFaceEmbeddingProvider:
    def __init__(
        self,
        *,
        model_name: str,
        cache_dir: str,# 模型缓存文件夹路径，避免重复下载模型
        normalized: bool = True,
    ) -> None:
        self.model_name = model_name
        self.cache_dir = cache_dir
        self.normalized = normalized
        # BAAI/bge-small-zh-v1.5 的固定维度使“删除最后一个文档”的空索引
        # 不必先编码一段虚构文本才能写入 manifest；非空构建仍会用真实向量形状校验。
        self.dimension: int | None = int(os.getenv("EMBEDDING_DIMENSION", "512"))
        self._embeddings: HuggingFaceEmbeddings | None = None# 懒加载：模型实例，初始是None

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            if self._embeddings is None:
                self._embeddings = HuggingFaceEmbeddings(
                    model_name=self.model_name,
                    cache_folder=self.cache_dir,#1. 模型缓存目录。- 第一次运行：从 HuggingFace Hub 下载模型权重，存到这个文件夹
                    model_kwargs={"device": "cpu"},#**模型加载时的参数**，控制模型放在哪个硬件上
                    encode_kwargs={"normalize_embeddings": self.normalized},#**文本编码生成向量阶段**的参数
                )
            vectors = self._embeddings.embed_documents(texts)
        except Exception as exc:  # provider errors must not expose model internals
            raise EmbeddingProviderError("embedding provider failed") from exc
            #安全自动探测向量维度；当没有向量时，不修改已有的维度值，避免触发数组越界报错。
        self.dimension = len(vectors[0]) if vectors else self.dimension
        #`map(float, vector)`：把向量里面每一个数字强制转为**Python 原生 float**
        return [list(map(float, vector)) for vector in vectors]


def create_embedding_provider_from_env() -> HuggingFaceEmbeddingProvider:
    return HuggingFaceEmbeddingProvider(
        model_name=os.getenv("LOCAL_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5").strip(),
        cache_dir=os.getenv("MODEL_CACHE_DIR", "./model-cache").strip(),
        normalized=True,
    )
