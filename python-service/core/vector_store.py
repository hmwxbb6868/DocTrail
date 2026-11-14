from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from uuid import UUID

"""向量存储抽象协议：只定义「向量库必须具备哪些能力、数据长什么样」，不写 FAISS 具体实现"""
class VectorStoreError(RuntimeError):
    """自定义异常类：**向量库相关错误专用异常**"""

#一条 chunk 记录
@dataclass(frozen=True)
class VectorPoint:
    chunk_id: int
    doc_id: int
    batch_id: UUID
    chunk_index: int
    text: str
    vector: list[float]# embedding向量，一堆浮点数 [0.12,0.34,...]


@dataclass(frozen=True)
class VectorMatch:
    score: float #向量相似度得分
    point: VectorPoint #向量分片的完整对象

#一份完整向量索引的「档案 / 说明书」,一整个索引包的整体描述，这一包里面包含很多 VectorPoint
@dataclass(frozen=True)
class IndexManifest:
    schema_version: str #索引文件结构版本号
    embedding_model: str #嵌入模型名称
    dimension: int #向量维度,VectorPoint 里面`vector: list[float]`数组的长度。
    normalized: bool #是否归一化,`True`= 归一化；`False`= 未归一化
    chunk_count: int #总 chunk 数,多少条分片 chunk（VectorPoint 数量）
    active_chunk_digest: str #活跃 chunk 摘要。算出一个哈希字符串。作用：校验 MySQL 分片 和 向量库的内容是否完全一致，防止两边数据不同步
    index_build_id: UUID #索引构建 ID,每 build 一次，生成全新唯一 UUID
    created_at: datetime #创建时间

#Python 协议（接口规范）：定义向量存储必须具备哪些能力、数据长什么样
class VectorStore(Protocol):
    def add_build(
        self,
        index_build_id: UUID,#本次构建的全局唯一编号
        points: list[VectorPoint],#本次构建的全局唯一编号
        *,
        embedding_model: str,
        dimension: int,
        normalized: bool,
        #当前 MySQL 所有 active 分片合并算出的哈希指纹，写入`IndexManifest`。
        #作用：校验向量库内的数据和 MySQL 的 active 分片集合是否一致，防止数据不同步。
        active_chunk_digest: str,
    ) -> IndexManifest: ...

    def activate(self, index_build_id: UUID) -> IndexManifest: ...

    def delete_by_build(self, index_build_id: UUID) -> None: ...

    def count(self) -> int: ...

    def manifest(self) -> IndexManifest | None: ...

    def search(self, query_vector: list[float], top_k: int = 10,
               filters: dict[str, object] | None = None) -> list[VectorMatch]: ...
