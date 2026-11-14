from __future__ import annotations

import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import UUID

import faiss
import numpy as np

from core.vector_store import IndexManifest, VectorMatch, VectorPoint, VectorStoreError


class FaissVectorStore:
    """这是一套基于 FAISS 实现的、支持多版本向量索引的向量存储类。
       可以多次构建多套独立向量版本（build），支持切换激活版本、删除旧版本、读取元信息；对外提供统一接口。"""

    _SCHEMA_VERSION = "1" # 索引元数据的结构版本，后续升级存储格式用
    _POINTER_FILE = "active.pointer" # 激活的向量库版本文件
    _INDEX_FILE = "index.faiss" # FAISS索引文件
    _POINTS_FILE = "points.json" # 存储VectorPoint的id、文本等元信息
    _MANIFEST_FILE = "index_manifest.json" ## IndexManifest元数据文件

    def __init__(self, persist_dir: str | Path) -> None:
        self._root = Path(persist_dir) #保存向量库的根文件夹路径
        #`parents=True`：如果上级文件夹不存在，一并创建
        #`exist_ok=True`：文件夹已经存在也**不抛异常**，不会重复创建报错
        self._root.mkdir(parents=True, exist_ok=True)

    def add_build(
        self,
        index_build_id: UUID,
        points: list[VectorPoint],
        *,
        embedding_model: str,
        dimension: int,
        normalized: bool,
        active_chunk_digest: str,
    ) -> IndexManifest:
        if dimension <= 0:
            raise VectorStoreError("FAISS build requires a positive dimension")
        # 空知识库是合法状态：仍然发布带有维度和零条记录的可用索引。
        vectors = np.asarray([point.vector for point in points], dtype=np.float32)
        #当 points 为空（没有分片），构造一个0 条向量、维度不变的 numpy 空数组，
        #用来满足向量构建接口入参格式要求，实现构建空索引
        if not points:
            vectors = np.empty((0, dimension), dtype=np.float32)
        #检查向量的维度是否与配置的索引维度匹配：`len(points)`：期望行数，`dimension`：期望列数
        if vectors.ndim != 2 or vectors.shape != (len(points), dimension):
            raise VectorStoreError("embedding dimension does not match the configured index dimension") #向量维度不匹配
        #如果**不是所有元素都是有限正常数字 → 抛异常
        if not np.isfinite(vectors).all():
            raise VectorStoreError("embedding vectors contain non-finite values") #向量包含非有限值

        #`index_build_id`是上层 DocumentService 用`uuid4()`生成的，理论上不会重复，但代码依然做防御。
        #创建向量库的版本目录：`build-{index_build_id}`
        version_dir = self._version_dir(index_build_id)
        # 判断这个目录在磁盘上是否已经存在
        if version_dir.exists():
            existing = self._read_manifest(version_dir)
            # 如果 existing 与传入的 index_build_id 相同，
            #说明本次 index_build_id 对应的索引早就完整构建成功了，返回IndexManifest
            if existing == index_build_id:
                return self._manifest_from_json(version_dir / self._MANIFEST_FILE)
            raise VectorStoreError("index_build_id already exists with different metadata")

        #新建一个**全量扫描、使用内积计算相似度**的 FAISS 索引，向量维度为 dimension
        index = faiss.IndexFlatIP(dimension)
        if len(points):
            index.add(vectors)
        created_at = datetime.now(timezone.utc)

        manifest = IndexManifest(
            schema_version=self._SCHEMA_VERSION,
            embedding_model=embedding_model,
            dimension=dimension,
            normalized=normalized,
            chunk_count=len(points),
            active_chunk_digest=active_chunk_digest,
            index_build_id=index_build_id,
            created_at=created_at,
        )

        #安全地把内存里构建好的整套向量索引保存到磁盘，利用临时目录 + 原子改名，
        #保证索引不会出现 “写一半、损坏残缺” 的状态；一旦保存失败就清理垃圾文件。
        temp_dir = self._root / f".tmp-{index_build_id}-{os.getpid()}"
        shutil.rmtree(temp_dir, ignore_errors=True)
        temp_dir.mkdir(parents=True)
        try:
            faiss.write_index(index, str(temp_dir / self._INDEX_FILE))
            self._write_json(temp_dir / self._POINTS_FILE, [_point_to_json(point) for point in points])
            self._write_json(temp_dir / self._MANIFEST_FILE, _manifest_to_json(manifest))
            os.replace(temp_dir, version_dir)
        except Exception as exc:
            shutil.rmtree(temp_dir, ignore_errors=True)
            raise VectorStoreError("failed to persist FAISS build") from exc
        return manifest

    #激活某一个已经构建好的索引版本，修改指针文件，把它设为当前正在使用的索引；返回这套索引的 manifest 档案。
    def activate(self, index_build_id: UUID) -> IndexManifest:
        version_dir = self._version_dir(index_build_id)
        if not version_dir.is_dir():
            raise VectorStoreError("cannot activate a missing FAISS build")
        manifest = self._manifest_from_json(version_dir / self._MANIFEST_FILE)
        pointer_temp = self._root / f".{self._POINTER_FILE}.{os.getpid()}.tmp"
        pointer_temp.write_text(str(index_build_id), encoding="utf-8")
        os.replace(pointer_temp, self._root / self._POINTER_FILE)
        return manifest

    #删除指定索引版本（build 目录），增加保护机制：不允许删除当前正在激活使用的索引。
    def delete_by_build(self, index_build_id: UUID) -> None:
        if self._active_id() == index_build_id:
            raise VectorStoreError("cannot delete the active FAISS build")
        shutil.rmtree(self._version_dir(index_build_id), ignore_errors=True)

    #**查询【当前激活版本】索引里面一共有多少条向量。**
    def count(self) -> int:
        active = self._active_dir()
        if active is None:
            return 0
        return int(faiss.read_index(str(active / self._INDEX_FILE)).ntotal)

    def manifest(self) -> IndexManifest | None:
        active = self._active_dir()
        return None if active is None else self._manifest_from_json(active / self._MANIFEST_FILE)

#   查询当前被激活的那个 FAISS 索引版本，最终返回向量检索结果
    def search(self, query_vector: list[float], top_k: int = 10,
               filters: dict[str, object] | None = None) -> list[VectorMatch]:
               #VectorMatch：相似度 score + 对应的向量分片 `VectorPoint`
               #top_k返回最相似的前 K 条结果
               #`query_vector` = 用户查询文本经过 Embedding 模型生成的浮点向量数组
               #`filters` 是检索时的过滤条件字典，可选参数，不传就是`None`，代表不做过滤
        active_id = self._active_id()
        return [] if active_id is None else self.search_build(active_id, query_vector, top_k, filters)

    #指定某一个索引版本（`index_build_id`）执行向量检索，是整套 FAISS 向量库**真正的底层检索核心逻辑**
    def search_build(self, index_build_id: UUID, query_vector: list[float], top_k: int = 10,
                     #例如：filters={"doc_id":100, "chunk_id":5}
                     filters: dict[str, object] | None = None) -> list[VectorMatch]:
        # ============================== 参数基础校验 ==================================
        if top_k <= 0:
            raise VectorStoreError("top_k must be positive")
        # 校验过滤器：只允许白名单内的字段做过滤，其他过滤键直接抛异常
        if filters and set(filters) - {"chunk_id", "doc_id", "batch_id", "chunk_index"}:
            raise VectorStoreError("unsupported search filter")
        # ======================== 定位索引版本目录 + 读取manifest元信息 =============================
        # 根据build_id找到该版本索引对应的目录
        active = self._version_dir(index_build_id)
        if not active.is_dir():
            raise VectorStoreError("FAISS build directory is missing")
            # 读取当前build的index_manifest.json清单文件，解析为IndexManifest对象
        manifest = self._manifest_from_json(active / self._MANIFEST_FILE)
        # ================================ 处理并校验用户查询向量 ==================================
        try:
            #query 就是用户当前这条提问，经过 Embedding 模型转出来的向量，再包装成 FAISS 需要的二维 numpy 数组
            # 将查询向量转为numpy浮点数组，shape [1,向量维度]，符合faiss入参格式
            query = np.asarray([query_vector], dtype=np.float32)
        except (TypeError, ValueError) as exc:
            raise VectorStoreError("query vector is invalid") from exc
            # 校验向量形状：必须是(1, 维度)，因为只能回答用户一条问题，并且向量所有值不能是无穷/NaN非法值
        if query.shape != (1, manifest.dimension) or not np.isfinite(query).all():
            raise VectorStoreError("query vector dimension is invalid")
            # 如果索引构建时开启了向量归一化，则对查询向量执行归一化（和建索引保持一致）
        if manifest.normalized:
            norm = float(np.linalg.norm(query[0]))
            if not np.isfinite(norm) or norm == 0.0:
                raise VectorStoreError("normalized query vector must have a finite positive norm")
            query = query / norm
            #==============================加载FALSS对象与points元数据=================================
        try:
            # 从磁盘读取 index.faiss 文件，加载成 FAISS 索引对象（index）
            index = faiss.read_index(str(active / self._INDEX_FILE))
            # 读取points.json，把json里的原始数据转换成VectorPoint对象列表
            points = [_point_from_json(item) for item in json.loads((active / self._POINTS_FILE).read_text(encoding="utf-8"))]
        except (OSError, RuntimeError, KeyError, TypeError, ValueError) as exc:
            raise VectorStoreError("FAISS build data is invalid") from exc
        # ================================= 索引与元数据一致性强校验 ========================================
            # 一致性强校验：
        if index.ntotal != len(points) or len(points) != manifest.chunk_count or index.d != manifest.dimension:
            raise VectorStoreError("FAISS point metadata count does not match the index")
            # 如果没有任何分片数据，直接返回空结果
        if not points:
            return []
        # ============================= 计算候选召回数量 search_k ===================================
            # 确定本次检索的候选数量：
            # 有过滤器时：让 FAISS 把索引里所有向量全部召回，召回之后，在 Python 内存里过滤
            # 无过滤器：只检索top_k个即可，减少计算量，直接让 FAISS 一次性召回相似度最高的 top_k 条候选。
        search_k = len(points) if filters else min(top_k, len(points))
        #==============================FALSS执行向量相似度检索======================
        # 调用faiss原生检索接口，执行向量相似度检索
        # scores：相似度分数；positions：命中向量在索引中的下标位置
        #根据用户问题的向量，告诉 FAISS：找出相似度最高的 search_k 条向量
        #只传入 1 条查询向量，所以两个数组都是 `shape=(1, search_k)`
        scores, positions = index.search(query, search_k)
        #`matches` 用来存放最终合格结果，元素是`VectorMatch`
        # =========================== 遍历召回结果、后置过滤、收集top_k合格结果 =============================
        matches: list[VectorMatch] = []
        #`zip(..., strict=True)`：分数和下标一一配对；两个数组长度不一致直接抛异常
        for score, position in zip(scores[0], positions[0], strict=True):
            #下标不等于0
            if position < 0:
                continue
                # 根据下标取出对应的VectorPoint对象
            point = points[int(position)]
            # 如果配置了过滤条件，循环校验每一条过滤规则
            # batch_id特殊处理，转为字符串对比；其余字段直接读取point的属性对比
            # 只要任意一条过滤条件不匹配，就跳过本条结果
            if filters and any((str(point.batch_id) if key == "batch_id" else getattr(point, key)) != value
                               for key, value in filters.items()):
                continue
            # 构造VectorMatch对象：相似度分数 + 分片VectorPoint，加入结果列表
            matches.append(VectorMatch(float(score), point))
            # 收集够top_k条结果，直接终止循环，不再继续遍历
            if len(matches) == top_k:
                break
        return matches

    #负责读取 `active.pointer` 文件，拿到当前被激活索引版本的 build_id（UUID），新建索引时就会把index_build_id写入active.pointer文件
    #`active.pointer` 里面不存 FAISS 向量索引，它只是一个很小的纯文本标记文件，只保存一个 UUID 字符串
    def _active_id(self) -> UUID | None:
        pointer = self._root / self._POINTER_FILE
        if not pointer.exists():
            return None
        try:
            return UUID(pointer.read_text(encoding="utf-8").strip())
        except (ValueError, OSError) as exc:
            raise VectorStoreError("active FAISS pointer is invalid") from exc

    def _active_dir(self) -> Path | None:
        active_id = self._active_id()
        if active_id is None:
            return None
        directory = self._version_dir(active_id)
        if not directory.is_dir():
            raise VectorStoreError("active FAISS build directory is missing")
        return directory

    #路径对象，表示向量库的版本目录：`build-{index_build_id}`文件夹
    def _version_dir(self, index_build_id: UUID) -> Path:
        return self._root / f"build-{index_build_id}"

    #`@staticmethod`：静态方法，不需要实例`self`，直接类调用，不访问实例变量
    @staticmethod
    def _write_json(path: Path, value: Any) -> None:
        path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2), encoding="utf-8")

    @classmethod
    #把整个 json 加载成`IndexManifest`对象，只取出`.index_build_id`返回
    def _read_manifest(cls, directory: Path) -> UUID:
        return cls._manifest_from_json(directory / cls._MANIFEST_FILE).index_build_id

    #底层核心，传入 json 文件路径，读取并完整解析，返回完整 IndexManifest 对象（`@staticmethod`）
    #`index_manifest.json` 是**调用 `add_build` 构建索引的时候，由代码主动生成并写入磁盘**的元信息文件
    #`path` 就是**磁盘上已经存在的 `index_manifest.json` 文件的完整路径**，eg.build-{index_build_id}/index_manifest.json
    @staticmethod
    def _manifest_from_json(path: Path) -> IndexManifest:
        try:
            # 读取文件文本，解析json字典
            value = json.loads(path.read_text(encoding="utf-8"))
            return IndexManifest(
                schema_version=value["schema_version"],
                embedding_model=value["embedding_model"],
                dimension=int(value["dimension"]),
                normalized=bool(value["normalized"]),
                chunk_count=int(value["chunk_count"]),
                active_chunk_digest=value["active_chunk_digest"],
                index_build_id=UUID(value["index_build_id"]),
                created_at=datetime.fromisoformat(value["created_at"].replace("Z", "+00:00")),
            )
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise VectorStoreError("FAISS manifest is invalid") from exc


def _manifest_to_json(manifest: IndexManifest) -> dict[str, Any]:
    return {
        "schema_version": manifest.schema_version,
        "embedding_model": manifest.embedding_model,
        "dimension": manifest.dimension,
        "normalized": manifest.normalized,
        "chunk_count": manifest.chunk_count,
        "active_chunk_digest": manifest.active_chunk_digest,
        "index_build_id": str(manifest.index_build_id),
        "created_at": manifest.created_at.isoformat().replace("+00:00", "Z"),
    }


def _point_to_json(point: VectorPoint) -> dict[str, Any]:
    return {
        "chunk_id": point.chunk_id,
        "doc_id": point.doc_id,
        "batch_id": str(point.batch_id),
        "chunk_index": point.chunk_index,
        "text": point.text,
    }

#把从 `points.json` 读出来的一条json 字典，转换成 Python 的强类型实体对象 `VectorPoint`。
#`points.json` 里每一条记录，对应知识库的一个文本分片（chunk）
def _point_from_json(value: dict[str, Any]) -> VectorPoint:
    return VectorPoint(chunk_id=int(value["chunk_id"]), doc_id=int(value["doc_id"]),
                       batch_id=UUID(value["batch_id"]), chunk_index=int(value["chunk_index"]),
                       text=str(value["text"]), vector=[])


def create_vector_store_from_env() -> FaissVectorStore:
    backend = os.getenv("VECTOR_STORE_BACKEND", "faiss").strip().lower()
    if backend != "faiss":
        raise VectorStoreError("stage 4 supports only VECTOR_STORE_BACKEND=faiss")
    return FaissVectorStore(os.getenv("VECTOR_STORE_PERSIST_DIR", "./faiss_index").strip())
