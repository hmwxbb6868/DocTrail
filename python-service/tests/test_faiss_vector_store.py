from uuid import uuid4
from dataclasses import replace

import pytest

from core.vector_store import VectorPoint, VectorStoreError
from infrastructure.faiss_vector_store import FaissVectorStore


def _point(index: int) -> VectorPoint:
    return VectorPoint(
        chunk_id=index + 1,
        doc_id=46,
        batch_id=uuid4(),
        chunk_index=index,
        text=f"chunk-{index}",
        vector=[1.0, 0.0],
    )


def test_faiss_build_manifest_and_atomic_activation(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()

    manifest = store.add_build(
        build_id,
        [_point(0), _point(1)],
        embedding_model="test-model",
        dimension=2,
        normalized=True,
        active_chunk_digest="digest",
    )

    assert store.manifest() is None
    assert manifest.chunk_count == 2
    store.activate(build_id)
    assert store.count() == 2
    assert store.manifest().index_build_id == build_id
    assert (tmp_path / "index_manifest.json").exists() is False
    assert (tmp_path / f"build-{build_id}" / "index_manifest.json").exists()


def test_faiss_rejects_dimension_mismatch_without_publishing(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    with pytest.raises(VectorStoreError, match="dimension"):
        store.add_build(
            uuid4(),
            [_point(0)],
            embedding_model="test-model",
            dimension=3,
            normalized=True,
            active_chunk_digest="digest",
        )
    assert list(tmp_path.iterdir()) == []


def test_failed_new_build_does_not_replace_existing_active_build(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    old_id = uuid4()
    store.add_build(
        old_id,
        [_point(0)],
        embedding_model="test-model",
        dimension=2,
        normalized=True,
        active_chunk_digest="old-digest",
    )
    store.activate(old_id)

    with pytest.raises(VectorStoreError, match="dimension"):
        store.add_build(
            uuid4(),
            [_point(0)],
            embedding_model="test-model",
            dimension=3,
            normalized=True,
            active_chunk_digest="new-digest",
        )

    assert store.manifest().index_build_id == old_id
    assert store.count() == 1


def test_faiss_can_publish_a_valid_empty_index(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()

    manifest = store.add_build(
        build_id,
        [],
        embedding_model="test-model",
        dimension=2,
        normalized=True,
        active_chunk_digest="empty-digest",
    )
    store.activate(build_id)

    assert manifest.chunk_count == 0
    assert store.count() == 0
    assert store.manifest().active_chunk_digest == "empty-digest"


def test_faiss_search_returns_point_metadata_and_score(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()
    store.add_build(build_id, [_point(0), replace(_point(1), vector=[0.0, 1.0])], embedding_model="test-model",
                    dimension=2, normalized=True, active_chunk_digest="digest")
    store.activate(build_id)
    matches = store.search([1.0, 0.0], top_k=1)
    assert len(matches) == 1
    assert matches[0].point.chunk_id == 1
    assert matches[0].point.text == "chunk-0"
    assert matches[0].score == pytest.approx(1.0)


def test_filtered_search_can_find_a_point_outside_unfiltered_top_k(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()
    store.add_build(build_id, [_point(0), replace(_point(1), doc_id=47, vector=[0.0, 1.0])],
                    embedding_model="test-model", dimension=2, normalized=True, active_chunk_digest="digest")
    store.activate(build_id)
    matches = store.search([1.0, 0.0], top_k=1, filters={"doc_id": 47})
    assert [match.point.chunk_id for match in matches] == [2]


def test_search_build_validates_unpublished_build_without_switching_pointer(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    old_id, new_id = uuid4(), uuid4()
    for build_id, point in [(old_id, _point(0)), (new_id, replace(_point(1), vector=[0.0, 1.0]))]:
        store.add_build(build_id, [point], embedding_model="test-model", dimension=2,
                        normalized=True, active_chunk_digest="digest")
    store.activate(old_id)
    assert store.search_build(new_id, [0.0, 1.0])[0].point.chunk_id == 2
    assert store.manifest().index_build_id == old_id


@pytest.mark.parametrize("vector", [[1.0], [float("nan"), 0.0], [0.0, 0.0]])
def test_search_rejects_invalid_query_vectors(tmp_path, vector) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()
    store.add_build(build_id, [_point(0)], embedding_model="test-model", dimension=2,
                    normalized=True, active_chunk_digest="digest")
    store.activate(build_id)
    with pytest.raises(VectorStoreError):
        store.search(vector)


def test_search_empty_build_returns_no_hits(tmp_path) -> None:
    store = FaissVectorStore(tmp_path)
    build_id = uuid4()
    store.add_build(build_id, [], embedding_model="test-model", dimension=2,
                    normalized=True, active_chunk_digest="digest")
    store.activate(build_id)
    assert store.search([1.0, 0.0]) == []
