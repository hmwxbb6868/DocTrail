from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from application.document_service import DocumentService, _active_chunk_digest
from core.vector_store import IndexManifest, VectorPoint, VectorStoreError
from infrastructure.mysql_chunk_store import ActiveChunk
from providers.embedding_provider import EmbeddingProviderError
from main import create_app


TRACE_ID = "15fcdb04-b282-4c39-a51d-4fb7f948b43c"


def _chunks() -> list[ActiveChunk]:
    return [ActiveChunk(101, 46, UUID("11111111-1111-1111-1111-111111111111"), 0, "可信正文")]


@dataclass
class RebuildChunkStore:
    chunks: list[ActiveChunk]

    def list_active_chunks(self) -> list[ActiveChunk]:
        return list(self.chunks)


@dataclass
class RebuildEmbeddingProvider:
    fail: bool = False
    model_name: str = "test-model"
    dimension: int | None = 2
    normalized: bool = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self.fail:
            raise EmbeddingProviderError("embedding failed")
        assert texts == ["可信正文"]
        return [[1.0, 0.0]]


class RebuildVectorStore:
    def __init__(self, chunks: list[ActiveChunk], *, bad_manifest: bool = False, fail_activate: bool = False) -> None:
        self.active_manifest = IndexManifest(
            schema_version="1",
            embedding_model="test-model",
            dimension=2,
            normalized=True,
            chunk_count=len(chunks),
            active_chunk_digest=_active_chunk_digest(chunks),
            index_build_id=uuid4(),
            created_at=datetime.now(timezone.utc),
        )
        self.published_builds: list[IndexManifest] = []
        self.added_builds: list[IndexManifest] = []
        self.bad_manifest = bad_manifest
        self.fail_activate = fail_activate

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
        manifest = IndexManifest(
            schema_version="1",
            embedding_model=embedding_model,
            dimension=dimension,
            normalized=normalized,
            chunk_count=len(points),
            active_chunk_digest="0" * 64 if self.bad_manifest else active_chunk_digest,
            index_build_id=index_build_id,
            created_at=datetime.now(timezone.utc),
        )
        self.added_builds.append(manifest)
        return manifest

    def activate(self, index_build_id: UUID) -> IndexManifest:
        if self.fail_activate:
            raise VectorStoreError("activation failed")
        manifest = next(item for item in self.added_builds if item.index_build_id == index_build_id)
        self.active_manifest = manifest
        self.published_builds.append(manifest)
        return manifest

    def delete_by_build(self, index_build_id: UUID) -> None:
        self.added_builds = [item for item in self.added_builds if item.index_build_id != index_build_id]

    def count(self) -> int:
        return self.active_manifest.chunk_count

    def manifest(self) -> IndexManifest:
        return self.active_manifest


def _service(
    *, fail: bool = False, bad_manifest: bool = False, fail_activate: bool = False
) -> tuple[DocumentService, RebuildVectorStore]:
    chunks = _chunks()
    vector_store = RebuildVectorStore(chunks, bad_manifest=bad_manifest, fail_activate=fail_activate)
    service = DocumentService(
        reader=None,
        parser=None,
        splitter=None,
        chunk_store=RebuildChunkStore(chunks),
        embedding_provider=RebuildEmbeddingProvider(fail=fail),
        vector_store=vector_store,
    )
    return service, vector_store


def test_rebuild_returns_new_activated_build_and_is_repeatable() -> None:
    service, vector_store = _service()
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    first = client.post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )
    second = client.post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["ready"] is True
    assert second.json()["ready"] is True
    assert first.json()["trace_id"] == TRACE_ID
    assert first.json()["index_build_id"] != second.json()["index_build_id"]
    assert vector_store.manifest().index_build_id == UUID(second.json()["index_build_id"])


def test_rebuild_failure_returns_503_and_keeps_previous_build() -> None:
    service, vector_store = _service(fail=True)
    old_build_id = vector_store.manifest().index_build_id
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    response = client.post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert response.json()["ready"] is False
    assert response.json()["index_build_id"] is None
    assert vector_store.manifest().index_build_id == old_build_id
    assert vector_store.published_builds == []


def test_rebuild_rejects_bad_manifest_before_activation() -> None:
    service, vector_store = _service(bad_manifest=True)
    old_build_id = vector_store.manifest().index_build_id
    response = TestClient(create_app(internal_token="test-token", document_service=service)).post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert vector_store.manifest().index_build_id == old_build_id
    assert vector_store.published_builds == []
    assert vector_store.added_builds == []


def test_rebuild_activation_failure_keeps_previous_build() -> None:
    service, vector_store = _service(fail_activate=True)
    old_build_id = vector_store.manifest().index_build_id
    response = TestClient(create_app(internal_token="test-token", document_service=service)).post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 503
    assert vector_store.manifest().index_build_id == old_build_id
    assert vector_store.published_builds == []
    assert vector_store.added_builds == []


def test_rebuild_requires_internal_token_and_valid_trace_header() -> None:
    service, _ = _service()
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    assert client.post(
        "/api/vector-store/rebuild",
        headers={"X-Trace-Id": TRACE_ID},
    ).status_code == 401
    assert client.post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": "not-a-uuid"},
    ).status_code == 400
    assert client.post(
        "/api/vector-store/rebuild",
        headers={"X-Internal-Token": "test-token"},
    ).status_code == 400
