import pytest

from core.text_splitter import DocumentTextSplitter, TextSplitConfigurationError


def test_splitter_preserves_order_and_respects_size() -> None:
    splitter = DocumentTextSplitter(chunk_size=20, chunk_overlap=4)

    chunks = splitter.split("第一段内容。\n\n第二段内容。\n\n第三段内容。")

    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))
    assert all(0 < len(chunk.content) <= 20 for chunk in chunks)
    assert "第一段内容" in chunks[0].content
    assert "第三段内容" in chunks[-1].content


@pytest.mark.parametrize(
    ("chunk_size", "chunk_overlap"),
    [(0, 0), (10, 10), (10, 11), (10_001, 1), (10, -1)],
)
def test_splitter_rejects_invalid_configuration(chunk_size: int, chunk_overlap: int) -> None:
    with pytest.raises(TextSplitConfigurationError):
        DocumentTextSplitter(chunk_size=chunk_size, chunk_overlap=chunk_overlap)


def test_splitter_reads_environment_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("KNOWLEDGE_CHUNK_SIZE", "64")
    monkeypatch.setenv("KNOWLEDGE_CHUNK_OVERLAP", "8")

    splitter = DocumentTextSplitter.from_env()

    assert splitter.chunk_size == 64
    assert splitter.chunk_overlap == 8
