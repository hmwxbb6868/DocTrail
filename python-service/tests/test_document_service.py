import hashlib
from dataclasses import dataclass
from uuid import uuid4

import pytest

from application.document_service import ContentHashMismatchError, DocumentProcessResult, DocumentService
from core.parser import DocumentParser
from core.text_splitter import DocumentTextSplitter, TextChunk
from infrastructure.mysql_chunk_store import ActiveChunk, ActiveDocumentBatch, ChunkBatch


@dataclass
class StaticReader:
    data: bytes

    def read(self, storage_key: str) -> bytes:
        assert storage_key == "pending/document.txt"
        return self.data


@dataclass
class StaticChunkStore:
    batch: ChunkBatch
    chunks: list[TextChunk] | None = None
    deactivated: bool = False
    replace_calls: int = 0

    def replace_active_chunks(self, doc_id: int, chunks: list[TextChunk]) -> ChunkBatch:
        assert doc_id == 46
        self.replace_calls += 1
        if self.replace_calls > 1:
            self.batch = ChunkBatch(uuid4(), len(chunks))
        self.chunks = chunks
        return self.batch

    def find_active_batch(self, doc_id: int, content_hash: str) -> ActiveDocumentBatch | None:
        if self.deactivated or self.chunks is None:
            return None
        return ActiveDocumentBatch(doc_id, content_hash, self.batch.batch_id, len(self.chunks))

    def list_active_chunks(self):
        if self.deactivated:
            return []
        return [
            ActiveChunk(101 + chunk.index, 46, self.batch.batch_id, chunk.index, chunk.content)
            for chunk in (self.chunks or [])
        ]

    def restore_previous_active_chunks(self, doc_id: int, batch: ChunkBatch) -> None:
        pass

    def delete_inactive_chunks(self, doc_id: int, keep_batch_id) -> None:
        pass

    def deactivate_document_chunks(self, doc_id: int):
        from infrastructure.mysql_chunk_store import DeletedChunkBatch
        self.deactivated = True
        return DeletedChunkBatch(doc_id, len(self.chunks or []), (str(self.batch.batch_id),))

    def restore_document_chunks(self, batch) -> None:
        self.deactivated = False

    def purge_inactive_document_chunks(self, doc_id: int) -> None:
        self.chunks = []


@dataclass
class StaticEmbeddingProvider:
    model_name: str = "test-model"
    dimension: int | None = 2
    normalized: bool = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [[1.0, 0.0] for _ in texts]


class StaticVectorStore:
    def __init__(self) -> None:
        self.manifest_value = None
        self.build_calls = 0

    def add_build(self, index_build_id, points, **kwargs):
        self.build_calls += 1
        from core.vector_store import IndexManifest
        from datetime import datetime, timezone
        self.manifest_value = IndexManifest(
            schema_version="1",
            embedding_model=kwargs["embedding_model"],
            dimension=kwargs["dimension"],
            normalized=kwargs["normalized"],
            chunk_count=len(points),
            active_chunk_digest=kwargs["active_chunk_digest"],
            index_build_id=index_build_id,
            created_at=datetime.now(timezone.utc),
        )
        return self.manifest_value

    def activate(self, index_build_id):
        return self.manifest_value

    def delete_by_build(self, index_build_id):
        return None

    def count(self):
        return 0 if self.manifest_value is None else self.manifest_value.chunk_count

    def manifest(self):
        return self.manifest_value


def test_document_service_verifies_hash_before_parsing() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(),
        StaticVectorStore(),
    )

    parsed = service.parse(46, "pending/document.txt", hashlib.sha256(data).hexdigest())

    assert isinstance(parsed, DocumentProcessResult)
    assert parsed.parsed.text == "可信正文"
    assert parsed.parsed.document_type == "text"
    assert parsed.chunk_count == 1
    assert store.chunks is not None


def test_document_service_reuses_completed_batch_without_writing_duplicate_chunks() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    vector_store = StaticVectorStore()
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(),
        vector_store,
    )
    content_hash = hashlib.sha256(data).hexdigest()

    first = service.parse(46, "pending/document.txt", content_hash)
    second = service.parse(46, "pending/document.txt", content_hash)

    assert second.batch_id == first.batch_id
    assert second.index_build_id == first.index_build_id
    assert second.chunk_count == first.chunk_count
    assert store.replace_calls == 1
    assert vector_store.build_calls == 1


def test_document_service_force_rebuilds_completed_batch() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    vector_store = StaticVectorStore()
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(),
        vector_store,
    )
    content_hash = hashlib.sha256(data).hexdigest()

    first = service.parse(46, "pending/document.txt", content_hash)
    second = service.parse(46, "pending/document.txt", content_hash, force=True)

    assert second.batch_id != first.batch_id
    assert second.index_build_id != first.index_build_id
    assert store.replace_calls == 2
    assert vector_store.build_calls == 2


def test_document_service_does_not_reuse_manifest_from_another_embedding_model() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    vector_store = StaticVectorStore()
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(model_name="model-a"),
        vector_store,
    )
    content_hash = hashlib.sha256(data).hexdigest()
    service.parse(46, "pending/document.txt", content_hash)

    vector_store.manifest_value = vector_store.manifest_value.__class__(
        schema_version=vector_store.manifest_value.schema_version,
        embedding_model="model-b",
        dimension=vector_store.manifest_value.dimension,
        normalized=vector_store.manifest_value.normalized,
        chunk_count=vector_store.manifest_value.chunk_count,
        active_chunk_digest=vector_store.manifest_value.active_chunk_digest,
        index_build_id=vector_store.manifest_value.index_build_id,
        created_at=vector_store.manifest_value.created_at,
    )

    old_batch_id = store.batch.batch_id
    result = service.parse(46, "pending/document.txt", content_hash)

    assert result.batch_id != old_batch_id
    assert store.replace_calls == 2
    assert vector_store.build_calls == 2


def test_document_service_rejects_hash_mismatch() -> None:
    service = DocumentService(
        StaticReader(b"actual"),
        DocumentParser(),
        DocumentTextSplitter(),
        StaticChunkStore(ChunkBatch(uuid4(), 1)),
    )

    with pytest.raises(ContentHashMismatchError, match="SHA-256"):
        service.parse(46, "pending/document.txt", hashlib.sha256(b"expected").hexdigest())


def test_document_service_deletes_document_and_publishes_empty_index() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(),
        StaticVectorStore(),
    )
    service.parse(46, "pending/document.txt", hashlib.sha256(data).hexdigest())

    result = service.delete(46)

    assert result.doc_id == 46
    assert result.deleted_chunk_count == 1
    assert store.deactivated is True
    assert store.chunks == []


def test_document_service_readiness_rebuilds_when_manifest_is_missing() -> None:
    data = "可信正文".encode()
    store = StaticChunkStore(ChunkBatch(uuid4(), 1))
    vector_store = StaticVectorStore()
    service = DocumentService(
        StaticReader(data),
        DocumentParser(),
        DocumentTextSplitter(),
        store,
        StaticEmbeddingProvider(),
        vector_store,
    )
    service.parse(46, "pending/document.txt", hashlib.sha256(data).hexdigest())
    vector_store.manifest_value = None

    result = service.readiness()

    assert result.ready is True
    assert {check.name for check in result.checks} == {"mysql_active_chunks", "faiss_index"}
