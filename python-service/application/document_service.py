import hashlib
import hmac
from dataclasses import dataclass
from datetime import datetime
from functools import wraps
from threading import RLock
from uuid import UUID, uuid4


"""
 对象存储原始文件 (bytes) → 哈希校验 → 提取文本 → 文本切分 → MySQL 分片版本替换
  → 构建向量索引 → 校验索引 → 激活索引 → 清理旧分片 → 返回成功结果
  """

from core.parser import (
    DocumentParseError,
    DocumentParser,
    EmptyDocumentError,
    ParsedDocument,
    UnsupportedDocumentTypeError,
)
from infrastructure.storage_reader import (
    ObjectReader,
    StorageConfigurationError,
    StoredObjectNotFoundError,
    StoredObjectReadError,
    StoredObjectTooLargeError,
    create_object_reader_from_env,
)
from infrastructure.mysql_chunk_store import (
    ActiveChunk,
    ChunkPersistenceError,
    ChunkStore,
    create_chunk_store_from_env,
)
from core.text_splitter import DocumentTextSplitter
from core.vector_store import VectorMatch, VectorPoint, VectorStore, VectorStoreError
from infrastructure.faiss_vector_store import create_vector_store_from_env
from providers.embedding_provider import (
    EmbeddingProvider,
    EmbeddingProviderError,
    create_embedding_provider_from_env,
)

#文件哈希校验失败
class ContentHashMismatchError(ValueError):
    pass

#存储中找不到这个文档
class DocumentNotFoundError(FileNotFoundError):
    pass

#文档太大
class DocumentTooLargeError(ValueError):
    pass

#不支持的文档类型
class DocumentUnsupportedError(ValueError):
    pass

#文档本身无效，解析失败
class DocumentInvalidError(ValueError):
    pass

#文档服务不可用
class DocumentServiceUnavailableError(RuntimeError):
    pass


class IndexBuildError(RuntimeError):
    def __init__(self, message: str, *, batch_id: UUID | None = None, chunk_count: int = 0) -> None:
        super().__init__(message)
        self.batch_id = batch_id
        self.chunk_count = chunk_count


_PROCESS_INDEX_LOCK = RLock()


def _index_locked(method):
    @wraps(method)
    def wrapped(self, *args, **kwargs):
        with _PROCESS_INDEX_LOCK:
            return method(self, *args, **kwargs)

    return wrapped


@dataclass(frozen=True)
class DocumentProcessResult:
    parsed: ParsedDocument #解析后的文档对象
    batch_id: UUID #本次分片入库操作的**批次 ID**
    chunk_count: int #成功存入数据库多少个分片
    index_build_id: UUID | None = None


@dataclass(frozen=True)
class DocumentDeleteResult:
    doc_id: int
    deleted_chunk_count: int
    index_build_id: UUID

#每一条代表一个组件的单独检测结果
@dataclass(frozen=True)
class ReadinessCheckResult:
    name: str
    status: str
    detail: str | None = None

#`ready: bool`全局总开关：`True` = 所有依赖组件都正常，服务就绪，可以接收解析请求
@dataclass(frozen=True)
class ReadinessResult:
    ready: bool
    checks: tuple[ReadinessCheckResult, ...]


#向量存储统计结果实体
@dataclass(frozen=True)
class VectorStoreStatsResult:
    backend: str #向量后端引擎名称，固定是 `"faiss"`
    index_build_id: UUID | None
    embedding_model: str | None
    dimension: int | None
    normalized: bool | None
    chunk_count: int | None
    active_chunk_digest: str | None
    created_at: datetime | None
    ready: bool


class DocumentService:
    def __init__(
        self,
        reader: ObjectReader,
        parser: DocumentParser,
        splitter: DocumentTextSplitter,
        chunk_store: ChunkStore,
        embedding_provider: EmbeddingProvider | None = None,
        vector_store: VectorStore | None = None,
    ) -> None:
        self._reader = reader
        self._parser = parser
        self._splitter = splitter
        self._chunk_store = chunk_store
        self._embedding_provider = embedding_provider
        self._vector_store = vector_store

    #向量检索
    @_index_locked
    def retrieve(self, question: str, top_k: int = 5) -> list[tuple[VectorMatch, ActiveChunk]]:
        if not question or not question.strip():
            raise DocumentServiceUnavailableError("question must not be blank")
        if self._embedding_provider is None or self._vector_store is None:
            raise DocumentServiceUnavailableError("vector retrieval is not configured")
        try:
            # 获取问题的向量表示
            vectors = self._embedding_provider.embed([question])
            # 验证向量表示的长度和维度
            if len(vectors) != 1 or self._embedding_provider.dimension is None:
                raise DocumentServiceUnavailableError("embedding provider returned an invalid query vector")
            active_chunks = self._chunk_store.list_active_chunks()

            #===========================检验FALSS索引=====================================
            # 获取 FAISS 索引的元数据信息
            manifest = self._vector_store.manifest()
            if (
                manifest is None
                or manifest.embedding_model != self._embedding_provider.model_name
                or manifest.dimension != self._embedding_provider.dimension
                or manifest.normalized != self._embedding_provider.normalized
                or manifest.chunk_count != len(active_chunks)
                or self._vector_store.count() != len(active_chunks)
                or manifest.active_chunk_digest != _active_chunk_digest(active_chunks)
            ):
                raise DocumentServiceUnavailableError("FAISS manifest does not match active chunks and embedding")
            #==============================执行向量相似度检索==================================
            matches = self._vector_store.search(vectors[0], top_k=top_k)
            #分片唯一标识chunk_id为键、分片实体ActiveChunk为值的哈希映射active
            active = {chunk.chunk_id: chunk for chunk in active_chunks}

            #==============================检索结果处理==================================
            resolved: list[tuple[VectorMatch, ActiveChunk]] = []
            for match in matches:
                chunk = active.get(match.point.chunk_id)
                if chunk is not None and chunk.doc_id == match.point.doc_id and chunk.batch_id == match.point.batch_id:
                    #保存向量匹配记录`VectorMatch`与对应的 MySQL 活跃分片实体`ActiveChunk`
                    resolved.append((match, chunk))
            return resolved
        except (ChunkPersistenceError, EmbeddingProviderError, VectorStoreError, OSError, RuntimeError) as exc:
            if isinstance(exc, DocumentServiceUnavailableError):
                raise
            raise DocumentServiceUnavailableError("formal FAISS retrieval failed") from exc


    #健康就绪检测接口，用来校验整套文档向量服务是否可以正常对外提供检索服务
    @_index_locked
    def readiness(self) -> ReadinessResult:
        """对账 MySQL active chunks 与当前 FAISS 版本，必要时重建派生索引。"""
        checks: list[ReadinessCheckResult] = []
        try:
            active_chunks = self._chunk_store.list_active_chunks()
        except ChunkPersistenceError as exc:
            return ReadinessResult(
                False,
                (ReadinessCheckResult("mysql_active_chunks", "DOWN", str(exc)),),
            )

        checks.append(
            ReadinessCheckResult(
                "mysql_active_chunks", "UP", f"{len(active_chunks)} active chunks"
            )
        )
        if self._embedding_provider is None or self._vector_store is None:
            checks.append(
                ReadinessCheckResult(
                    "faiss_index", "DOWN", "embedding provider or vector store is not configured"
                )
            )
            return ReadinessResult(False, tuple(checks))

        mismatch = False
        manifest = None
        index_count = 0
        try:
            #获取 FAISS 索引的元数据信息
            manifest = self._vector_store.manifest()
            index_count = self._vector_store.count()
            expected_digest = _active_chunk_digest(active_chunks)
            mismatch = (
                manifest is None
                or index_count != len(active_chunks)
                or manifest.embedding_model != self._embedding_provider.model_name
                or manifest.dimension != self._embedding_provider.dimension
                or manifest.normalized != self._embedding_provider.normalized
                or manifest.chunk_count != len(active_chunks)
                or manifest.active_chunk_digest != expected_digest
            )
        except (VectorStoreError, OSError, RuntimeError):
            # 指针、构建目录或 manifest 损坏属于可重建的派生数据不一致。
            mismatch = True

        if mismatch:
            try:
                #重建 FAISS 索引
                manifest = self._rebuild_active_index(active_chunks)
                index_count = self._vector_store.count()
            except (ChunkPersistenceError, EmbeddingProviderError, IndexBuildError, VectorStoreError) as exc:
                checks.append(ReadinessCheckResult("faiss_index", "DOWN", f"rebuild failed: {exc}"))
                return ReadinessResult(False, tuple(checks))

        if (
            manifest is None
            or index_count != len(active_chunks)
            or manifest.embedding_model != self._embedding_provider.model_name
            or manifest.dimension != self._embedding_provider.dimension
            or manifest.normalized != self._embedding_provider.normalized
            or manifest.chunk_count != len(active_chunks)
            or manifest.active_chunk_digest != _active_chunk_digest(active_chunks)
        ):
            checks.append(ReadinessCheckResult("faiss_index", "DOWN", "FAISS remains inconsistent with active chunks"))
            return ReadinessResult(False, tuple(checks))

        checks.append(
            ReadinessCheckResult(
                "faiss_index", "UP", f"{manifest.index_build_id} with {index_count} vectors"
            )
        )
        return ReadinessResult(True, tuple(checks))


    @_index_locked
    def stats(self) -> VectorStoreStatsResult:
        """Read the active formal index metadata without repairing or mutating it."""
        try:
            active_chunks = self._chunk_store.list_active_chunks()
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError("failed to read active chunks") from exc

        if self._embedding_provider is None or self._vector_store is None:
            return VectorStoreStatsResult(
                backend="faiss",
                index_build_id=None,
                embedding_model=None,
                dimension=None,
                normalized=None,
                chunk_count=None,
                active_chunk_digest=None,
                created_at=None,
                ready=False,
            )

        try:
            manifest = self._vector_store.manifest()
            index_count = self._vector_store.count()
        except (VectorStoreError, OSError, RuntimeError) as exc:
            raise DocumentServiceUnavailableError("failed to read formal FAISS index") from exc

        if manifest is None:
            return VectorStoreStatsResult(
                backend="faiss",
                index_build_id=None,
                embedding_model=None,
                dimension=None,
                normalized=None,
                chunk_count=None,
                active_chunk_digest=None,
                created_at=None,
                ready=False,
            )

        ready = (
            self._embedding_provider.dimension is not None
            and manifest.embedding_model == self._embedding_provider.model_name
            and manifest.dimension == self._embedding_provider.dimension
            and manifest.normalized == self._embedding_provider.normalized
            and manifest.chunk_count == len(active_chunks)
            and index_count == len(active_chunks)
            and manifest.active_chunk_digest == _active_chunk_digest(active_chunks)
        )
        return VectorStoreStatsResult(
            backend="faiss",
            index_build_id=manifest.index_build_id,
            embedding_model=manifest.embedding_model,
            dimension=manifest.dimension,
            normalized=manifest.normalized,
            chunk_count=manifest.chunk_count,
            active_chunk_digest=manifest.active_chunk_digest,
            created_at=manifest.created_at,
            ready=ready,
        )

    @_index_locked
    #`rebuild_index()`：主动触发重建，对外暴露 API `/api/vector-store/rebuild`，由人 / 运维脚本调用。
    def rebuild_index(self) -> VectorStoreStatsResult:
        """强制基于 MySQL 当前所有活跃分片 `active_chunks`，重新完整构建一套全新 FAISS 索引版本。"""
        try:
            active_chunks = self._chunk_store.list_active_chunks()
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError("failed to read active chunks") from exc

        try:
            manifest = self._rebuild_active_index(active_chunks)
        except (EmbeddingProviderError, IndexBuildError, VectorStoreError) as exc:
            raise DocumentServiceUnavailableError("failed to rebuild formal FAISS index") from exc
        try:
            index_count = self._vector_store.count() if self._vector_store is not None else 0
        except (VectorStoreError, OSError, RuntimeError) as exc:
            raise DocumentServiceUnavailableError("failed to read rebuilt FAISS index") from exc

        if (
            self._embedding_provider is None
            or self._vector_store is None
            or manifest.embedding_model != self._embedding_provider.model_name
            or manifest.dimension != self._embedding_provider.dimension
            or manifest.normalized != self._embedding_provider.normalized
            or manifest.chunk_count != len(active_chunks)
            or index_count != len(active_chunks)
            or manifest.active_chunk_digest != _active_chunk_digest(active_chunks)
        ):
            raise DocumentServiceUnavailableError("rebuilt FAISS index is inconsistent with active chunks")

        return VectorStoreStatsResult(
            backend="faiss",
            index_build_id=manifest.index_build_id,
            embedding_model=manifest.embedding_model,
            dimension=manifest.dimension,
            normalized=manifest.normalized,
            chunk_count=manifest.chunk_count,
            active_chunk_digest=manifest.active_chunk_digest,
            created_at=manifest.created_at,
            ready=True,
        )



    #文档读取 + 校验 + 解析的上层入口方法
    @_index_locked
    def parse(
        self,
        doc_id: int,
        storage_key: str, #读文件的那一层，能力上就只认 key，不认识 doc_id
        expected_hash: str, #expected_hash 是原始文件字节的 SHA-256
        force: bool = False, #允许复用旧索引
    ) -> DocumentProcessResult:
        try:
            data = self._reader.read(storage_key)
        except StoredObjectNotFoundError as exc:
            raise DocumentNotFoundError(str(exc)) from exc
        except StoredObjectTooLargeError as exc:
            raise DocumentTooLargeError(str(exc)) from exc
        except StoredObjectReadError as exc:
            raise DocumentServiceUnavailableError(str(exc)) from exc
        #对刚刚读出来的文件二进制，计算 SHA256 哈希字符串
        actual_hash = hashlib.sha256(data).hexdigest()
        #比较计算出的哈希字符串与传入的哈希字符串是否匹配
        if not hmac.compare_digest(actual_hash, expected_hash):
            raise ContentHashMismatchError("stored document SHA-256 does not match content_hash")
        try:
            #**解析这堆二进制，把 PDF/DOCX 里面的文字提取出来**，变成可读文本，并且包装成`ParsedDocument`对象给上层业务
            parsed = self._parser.parse(storage_key, data)
        except UnsupportedDocumentTypeError as exc:
            raise DocumentUnsupportedError(str(exc)) from exc
        except (EmptyDocumentError, DocumentParseError) as exc:
            raise DocumentInvalidError(str(exc)) from exc

        chunks = self._splitter.split(parsed.text)

        #文件内容不变，，`_reusable_result`查到分片一致、向量索引一致，就直接返回之前的结果
        if not force:
            reusable = self._reusable_result(doc_id, expected_hash, parsed)
            if reusable is not None:
                return reusable

        try:
            #把本次切分好的新文本分片写入 MySQL，同时做软版本切换：旧分片标记失效，新分片标记为当前活跃。
            batch = self._chunk_store.replace_active_chunks(doc_id, chunks)
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError(str(exc)) from exc

        if self._embedding_provider is None or self._vector_store is None:
            #把新分片写入 MySQL 并切换为 active，但发现没有配置向量库 /embedding，无法继续构建 FAISS 索引；
            #把 MySQL 分片状态还原回更新前的样子，避免数据库和向量库状态不一致
            self._restore_after_index_failure(doc_id, batch, "vector index is not configured")
            raise IndexBuildError(
                "vector index is not configured",
                batch_id=batch.batch_id,
                chunk_count=batch.chunk_count,
            )

        index_build_id: UUID | None = None
        activated = False
        try:
            active_chunks = self._chunk_store.list_active_chunks()
            texts = [chunk.content for chunk in active_chunks]
            #文本-->向量列表
            vectors = self._embedding_provider.embed(texts)
            dimension = self._embedding_provider.dimension
            if dimension is None or len(vectors) != len(active_chunks):
                raise IndexBuildError("embedding provider returned an invalid vector set")
            points = [
                VectorPoint(
                    chunk_id=chunk.chunk_id,
                    doc_id=chunk.doc_id,
                    batch_id=chunk.batch_id,
                    chunk_index=chunk.chunk_index,
                    text=chunk.content,
                    vector=vector,
                )
                for chunk, vector in zip(active_chunks, vectors, strict=True)
            ]

            index_build_id = uuid4()
            manifest = self._vector_store.add_build(
                index_build_id,
                points,
                embedding_model=self._embedding_provider.model_name,
                dimension=dimension,
                normalized=self._embedding_provider.normalized,
                active_chunk_digest=_active_chunk_digest(active_chunks),
            )

           #一致性校验，确保 FAISS 新构建出来的索引和 MySQL 的活跃分片完全匹配
            expected_digest = _active_chunk_digest(active_chunks)
            if (
                manifest.chunk_count != len(active_chunks)
                or manifest.dimension != dimension
                or manifest.active_chunk_digest != expected_digest
            ):
                raise IndexBuildError("FAISS manifest does not match active chunks")

            #激活后目标版本是否一致
            active_manifest = self._vector_store.activate(index_build_id)
            if active_manifest.index_build_id != index_build_id:
                raise IndexBuildError("FAISS activation returned the wrong index build")

            activated = True
            try:
                #删除指定索引版本（build 目录），增加保护机制：不允许删除当前正在激活使用的索引。
                self._chunk_store.delete_inactive_chunks(doc_id, batch.batch_id)
            except ChunkPersistenceError:
                # Cleanup is derived-data housekeeping. The active index and DB batch
                # are already consistent, so a cleanup retry must not roll them back.
                pass
            return DocumentProcessResult(
                parsed=parsed,
                batch_id=batch.batch_id,
                chunk_count=batch.chunk_count,
                index_build_id=index_build_id,
            )
        # 捕获【预期内的业务异常】：数据库分片异常、嵌入模型异常、索引构建异常、FAISS向量库异常
        except (ChunkPersistenceError, EmbeddingProviderError, IndexBuildError, VectorStoreError) as exc:
            # 如果已经生成了新索引版本，并且还没有激活上线（半成品索引）
            if index_build_id is not None and not activated:
                try:
                    # 删除这个半成品FAISS索引版本，清理磁盘垃圾
                    self._vector_store.delete_by_build(index_build_id)
                except VectorStoreError:
                    # 删除索引本身失败，直接忽略，不影响主异常处理
                    pass
            # 执行分片回滚：把MySQL的chunk恢复到本次更新之前的旧版本，保证数据库状态安全
            self._restore_after_index_failure(doc_id, batch, str(exc))
            # 如果根源是MySQL分片持久化失败 → 包装成「服务不可用异常」向上抛出
            if isinstance(exc, ChunkPersistenceError):
                raise DocumentServiceUnavailableError(str(exc)) from exc
                # 如果本身就是IndexBuildError，并且异常携带batch_id → 原样抛出，不做包装，保留原始异常信息
            if isinstance(exc, IndexBuildError) and exc.batch_id is not None:
                raise
            # 其余几种异常（EmbeddingProviderError / VectorStoreError）统一包装为IndexBuildError向上抛出
            # from exc 保留原始异常堆栈，方便日志定位根因
            raise IndexBuildError(
                str(exc), batch_id=batch.batch_id, chunk_count=batch.chunk_count
            ) from exc
            # 兜底捕获【所有其它未预料的异常】：代码bug、数组越界、内存、IO等非预期异常
        except Exception as exc:
            if index_build_id is not None and not activated:
                try:
                    self._vector_store.delete_by_build(index_build_id)
                except VectorStoreError:
                    # 删除索引失败直接忽略
                    pass
            # 执行MySQL分片回滚，恢复旧分片
            self._restore_after_index_failure(doc_id, batch, "index build failed")
            # 统一封装为IndexBuildError，固定提示消息，带上本次批次信息，保留原始异常栈
            raise IndexBuildError(
                "index build failed", batch_id=batch.batch_id, chunk_count=batch.chunk_count
            ) from exc

    #构建全新 FAISS 索引版本、激活这个新版本索引，并且返回新的 manifest
    def _rebuild_active_index(self, active_chunks: list[ActiveChunk]):
        if self._embedding_provider is None or self._vector_store is None:
            raise IndexBuildError("vector index is not configured")
        texts = [chunk.content for chunk in active_chunks]
        vectors = self._embedding_provider.embed(texts)
        dimension = self._embedding_provider.dimension
        if dimension is None or len(vectors) != len(active_chunks):
            raise IndexBuildError("embedding provider returned an invalid vector set")
        points = [
            VectorPoint(
                chunk_id=chunk.chunk_id,
                doc_id=chunk.doc_id,
                batch_id=chunk.batch_id,
                chunk_index=chunk.chunk_index,
                text=chunk.content,
                vector=vector,
            )
            for chunk, vector in zip(active_chunks, vectors, strict=True)
        ]
        index_build_id = uuid4()
        activated = False
        try:
            manifest = self._vector_store.add_build(
                index_build_id,
                points,
                embedding_model=self._embedding_provider.model_name,
                dimension=dimension,
                normalized=self._embedding_provider.normalized,
                active_chunk_digest=_active_chunk_digest(active_chunks),
            )
            if (
                manifest.chunk_count != len(active_chunks)
                or manifest.dimension != dimension
                or manifest.active_chunk_digest != _active_chunk_digest(active_chunks)
            ):
                raise IndexBuildError("FAISS manifest does not match active chunks")
            active_manifest = self._vector_store.activate(index_build_id)
            activated = True
            return active_manifest
        except (EmbeddingProviderError, IndexBuildError, VectorStoreError):
            if not activated:
                try:
                    self._vector_store.delete_by_build(index_build_id)
                except VectorStoreError:
                    pass
            raise

#用 `parse()` 处理文档时，如果文档内容没变（校验本次读到的文件二进制哈希）
#在该函数内检验分片、向量索引也完全一致，直接返回上次处理成功的结果
    def _reusable_result(
        self,
        doc_id: int,
        expected_hash: str,
        parsed: ParsedDocument,
    ) -> DocumentProcessResult | None:
        try:
            existing = self._chunk_store.find_active_batch(doc_id, expected_hash)
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError(str(exc)) from exc

        if existing is None or self._embedding_provider is None or self._vector_store is None:
            return None
        try:
            active_chunks = self._chunk_store.list_active_chunks()
            manifest = self._vector_store.manifest()
        except (ChunkPersistenceError, VectorStoreError) as exc:
            raise DocumentServiceUnavailableError(str(exc)) from exc
        if manifest is None:
            return None
        if (
        # 1. 文档原始文件哈希（兜底校验，文件内容没变）
            existing.content_hash != expected_hash
            # 2. 旧批次分片数量不能是0或者负数（非法批次直接禁用复用）
            or existing.chunk_count <= 0
            # 3. 嵌入模型名称、维度、归一化设置必须和当前配置完全一致
            or manifest.embedding_model != self._embedding_provider.model_name
            or manifest.dimension != self._embedding_provider.dimension
            or manifest.normalized != self._embedding_provider.normalized
            # 4. 分片数量必须一致
            or manifest.chunk_count != len(active_chunks)
            # 5. 激活分片的哈希必须一致， digest 校验是"全库"级别的
            or manifest.active_chunk_digest != _active_chunk_digest(active_chunks)
            # ========== 【校验：当前文档的旧批次分片完整存活在活跃分片里】 ==========
                # 统计MySQL现在活跃分片里，属于当前doc_id + 旧batch_id的分片条数
                # 必须等于旧批次记录的分片总数，说明这个文档的分片一条都没丢、没增减
            or sum(
                1
                for chunk in active_chunks
                if chunk.doc_id == doc_id and chunk.batch_id == existing.batch_id
            ) != existing.chunk_count
        ):
            return None
        return DocumentProcessResult(
            parsed=parsed,
            batch_id=existing.batch_id,
            chunk_count=existing.chunk_count,
            index_build_id=manifest.index_build_id,
        )

#   向量索引构建失败之后，触发切片状态回滚；调用 chunk_store，把这份文档的切片切回更新前的有效版本。
#   如果回滚本身也失败，就抛出文档服务不可用异常。
    def _restore_after_index_failure(self, doc_id: int, batch, reason: str) -> None:
        try:
            self._chunk_store.restore_previous_active_chunks(doc_id, batch)
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError(
                f"{reason}; failed to restore previous active chunks"
            ) from exc

    @_index_locked
    def delete(self, doc_id: int) -> DocumentDeleteResult:
        #DocumentDeleteResult：doc_id，  deleted_chunk_count，   index_build_id:
        try:
            deleted = self._chunk_store.deactivate_document_chunks(doc_id)
        except ChunkPersistenceError as exc:
            raise DocumentServiceUnavailableError(str(exc)) from exc

        index_build_id: UUID | None = None
        activated = False
        try:
            active_chunks = self._chunk_store.list_active_chunks()
            texts = [chunk.content for chunk in active_chunks]
            vectors = self._embedding_provider.embed(texts) if self._embedding_provider else None
            dimension = self._embedding_provider.dimension if self._embedding_provider else None
            if (
                vectors is None
                or self._vector_store is None
                or dimension is None
                or len(vectors) != len(active_chunks)
            ):
                raise IndexBuildError("vector index is not configured")
            points = [
                VectorPoint(
                    chunk_id=chunk.chunk_id,
                    doc_id=chunk.doc_id,
                    batch_id=chunk.batch_id,
                    chunk_index=chunk.chunk_index,
                    text=chunk.content,
                    vector=vector,
                )
                for chunk, vector in zip(active_chunks, vectors, strict=True)
            ]
            index_build_id = uuid4()
            manifest = self._vector_store.add_build(
                index_build_id,
                points,
                embedding_model=self._embedding_provider.model_name,
                dimension=dimension,
                normalized=self._embedding_provider.normalized,
                active_chunk_digest=_active_chunk_digest(active_chunks),
            )
            if manifest.chunk_count != len(active_chunks) or manifest.dimension != dimension:
                raise IndexBuildError("FAISS manifest does not match active chunks")
            self._vector_store.activate(index_build_id)
            activated = True
            try:
                self._chunk_store.purge_inactive_document_chunks(doc_id)
            except ChunkPersistenceError:
                # 索引与 active 集合已一致，清理可在后续 housekeeping 重试。
                pass
            return DocumentDeleteResult(doc_id, deleted.deleted_chunk_count, index_build_id)
        except (ChunkPersistenceError, EmbeddingProviderError, IndexBuildError, VectorStoreError) as exc:
            if index_build_id is not None and not activated:
                try:
                    self._vector_store.delete_by_build(index_build_id)
                except VectorStoreError:
                    pass
            try:
                self._chunk_store.restore_document_chunks(deleted)
            except ChunkPersistenceError as restore_exc:
                raise DocumentServiceUnavailableError(
                    "delete failed and previous chunks could not be restored"
                ) from restore_exc
            if isinstance(exc, ChunkPersistenceError):
                raise DocumentServiceUnavailableError(str(exc)) from exc
            raise IndexBuildError(str(exc), chunk_count=deleted.deleted_chunk_count) from exc


def create_document_service_from_env() -> DocumentService:
    try:
        #创建文件读取对象
        reader = create_object_reader_from_env()
    except StorageConfigurationError as exc:
        raise DocumentServiceUnavailableError(str(exc)) from exc
    try:
        #创建文本切分器 + MySQL 分片存储器
        splitter = DocumentTextSplitter.from_env()
        chunk_store = create_chunk_store_from_env()
    except (ChunkPersistenceError, ValueError) as exc:
        raise DocumentServiceUnavailableError(str(exc)) from exc
    try:
        embedding_provider = create_embedding_provider_from_env()
        vector_store = create_vector_store_from_env()
    except (EmbeddingProviderError, VectorStoreError, ValueError, OSError) as exc:
        raise DocumentServiceUnavailableError(str(exc)) from exc
    return DocumentService(
        reader,
        DocumentParser(),
        splitter,
        chunk_store,
        embedding_provider,
        vector_store,
    )

#当前 MySQL 里全部生效分片`ActiveChunk`,算出一个 SHA256 哈希摘要字符串，
#这个摘要存入`IndexManifest`，也就是前面代码里的`active_chunk_digest`。
def _active_chunk_digest(chunks: list[ActiveChunk]) -> str:
    payload = "\n".join(
        f"{chunk.chunk_id}:{chunk.doc_id}:{chunk.batch_id}:{chunk.chunk_index}:{chunk.content}"
        for chunk in chunks
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()
