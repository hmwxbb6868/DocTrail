from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from application.document_service import (
    DocumentService,
    DocumentServiceUnavailableError,
    VectorStoreStatsResult,
)
from core.vector_store import IndexManifest
from infrastructure.mysql_chunk_store import ActiveChunk
from main import create_app


TRACE_ID = "15fcdb04-b282-4c39-a51d-4fb7f948b43c"


@dataclass
class StatsChunkStore:
    chunks: list[ActiveChunk]
    writes: int = 0

    def list_active_chunks(self) -> list[ActiveChunk]:
        return list(self.chunks)


@dataclass
class StatsEmbeddingProvider:
    model_name: str = "test-model"
    dimension: int | None = 2
    normalized: bool = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        raise AssertionError("stats must not embed")


class StatsVectorStore:
    def __init__(self, manifest: IndexManifest | None, count: int | None = None) -> None:
        self._manifest = manifest
        self._count = count if count is not None else (manifest.chunk_count if manifest else 0)
        self.writes = 0

    def manifest(self) -> IndexManifest | None:
        return self._manifest

    def count(self) -> int:
        return self._count

    def add_build(self, *args, **kwargs):
        self.writes += 1
        raise AssertionError("stats must not build")

    def activate(self, *args, **kwargs):
        self.writes += 1
        raise AssertionError("stats must not activate")

    def delete_by_build(self, *args, **kwargs):
        self.writes += 1
        raise AssertionError("stats must not delete")


def _chunks() -> list[ActiveChunk]:
    return [ActiveChunk(101, 46, UUID("11111111-1111-1111-1111-111111111111"), 0, "可信正文")]


def _manifest(chunks: list[ActiveChunk], *, model: str = "test-model") -> IndexManifest:
    from application.document_service import _active_chunk_digest

    return IndexManifest(
        schema_version="1",
        embedding_model=model,
        dimension=2,
        normalized=True,
        chunk_count=len(chunks),
        active_chunk_digest=_active_chunk_digest(chunks),
        index_build_id=uuid4(),
        created_at=datetime.now(timezone.utc),
    )


def _service(*, manifest: IndexManifest | None, count: int | None = None) -> tuple[DocumentService, StatsChunkStore, StatsVectorStore]:
    chunks = _chunks()
    chunk_store = StatsChunkStore(chunks)
    vector_store = StatsVectorStore(manifest, count)
    service = DocumentService(
        reader=None,
        parser=None,
        splitter=None,
        chunk_store=chunk_store,
        embedding_provider=StatsEmbeddingProvider(),
        vector_store=vector_store,
    )
    return service, chunk_store, vector_store


def test_stats_returns_formal_metadata_when_consistent() -> None:
    service, chunk_store, vector_store = _service(manifest=_manifest(_chunks()))
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    response = client.get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["backend"] == "faiss"
    assert body["ready"] is True
    assert body["chunk_count"] == 1
    assert body["trace_id"] == TRACE_ID
    assert chunk_store.writes == 0
    assert vector_store.writes == 0


def test_stats_returns_503_without_manifest_and_does_not_rebuild() -> None:
    service, chunk_store, vector_store = _service(manifest=None)
    response = TestClient(create_app(internal_token="test-token", document_service=service)).get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert response.json()["ready"] is False
    assert response.json()["index_build_id"] is None
    assert chunk_store.writes == 0
    assert vector_store.writes == 0


def test_stats_returns_503_when_manifest_is_inconsistent_without_writes() -> None:
    service, chunk_store, vector_store = _service(manifest=_manifest(_chunks(), model="other-model"))
    response = TestClient(create_app(internal_token="test-token", document_service=service)).get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert response.json()["ready"] is False
    assert response.json()["embedding_model"] == "other-model"
    assert chunk_store.writes == 0
    assert vector_store.writes == 0


def test_stats_maps_storage_failure_to_503() -> None:
    class BrokenService:
        def stats(self):
            raise DocumentServiceUnavailableError("storage unavailable")

    response = TestClient(create_app(internal_token="test-token", document_service=BrokenService())).get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert response.json() == {
        "backend": "faiss",
        "index_build_id": None,
        "embedding_model": None,
        "dimension": None,
        "normalized": None,
        "chunk_count": None,
        "active_chunk_digest": None,
        "created_at": None,
        "ready": False,
        "trace_id": TRACE_ID,
    }


def test_stats_requires_internal_token_and_valid_trace_header() -> None:
    service, _, _ = _service(manifest=_manifest(_chunks()))
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    assert client.get("/api/vector-store/stats", headers={"X-Trace-Id": TRACE_ID}).status_code == 401
    assert client.get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": "not-a-uuid"},
    ).status_code == 400
    assert client.get(
        "/api/vector-store/stats",
        headers={"X-Internal-Token": "test-token"},
    ).status_code == 400


def test_document_stats_is_read_only_when_inconsistent() -> None:
    service, chunk_store, vector_store = _service(manifest=_manifest(_chunks(), model="other-model"))

    result: VectorStoreStatsResult = service.stats()

    assert result.ready is False
    assert result.index_build_id == vector_store.manifest().index_build_id
    assert chunk_store.writes == 0
    assert vector_store.writes == 0
