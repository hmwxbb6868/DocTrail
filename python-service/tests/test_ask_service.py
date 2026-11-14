from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent.state import AgentRunStatus, AgentStepStatus, AgentToolCallStatus
from application.ask_service import AskHistoryMessage, AskService
from application.document_service import DocumentService, DocumentServiceUnavailableError, _active_chunk_digest
from core.vector_store import IndexManifest, VectorMatch, VectorPoint
from infrastructure.mysql_chunk_store import ActiveChunk


class StaticChunks:
    def __init__(self, chunks: list[ActiveChunk]) -> None:
        self.chunks = chunks

    def list_active_chunks(self) -> list[ActiveChunk]:
        return self.chunks


class StaticEmbeddings:
    model_name = "test-embedding"
    dimension = 2
    normalized = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        assert texts == ["怎么建立 TCP 连接？"]
        return [[1.0, 0.0]]


class StaticVectorStore:
    def __init__(self, chunks: list[ActiveChunk], *, digest: str | None = None) -> None:
        self.point = VectorPoint(101, 46, chunks[0].batch_id, 0, chunks[0].content, [1.0, 0.0])
        self._manifest = IndexManifest(
            schema_version="1",
            embedding_model="test-embedding",
            dimension=2,
            normalized=True,
            chunk_count=len(chunks),
            active_chunk_digest=_active_chunk_digest(chunks) if digest is None else digest,
            index_build_id=uuid4(),
            created_at=datetime.now(timezone.utc),
        )

    def manifest(self):
        return self._manifest

    def count(self) -> int:
        return self._manifest.chunk_count

    def search(self, query_vector: list[float], top_k: int = 10, filters=None):
        assert query_vector == [1.0, 0.0]
        assert top_k == 5
        return [VectorMatch(0.91, self.point)]


class CapturingAnswerModel:
    def __init__(self) -> None:
        self.context = None
        self.history = None

    def answer(self, question: str, context: str, history: list[tuple[str, str]]):
        self.context = context
        self.history = history
        return "TCP 通过三次握手建立连接。", None


def _active_chunk() -> ActiveChunk:
    return ActiveChunk(101, 46, uuid4(), 0, "TCP 使用三次握手建立连接。", "网络基础示例.md")


def test_document_service_resolves_faiss_match_only_to_matching_active_chunk() -> None:
    chunk = _active_chunk()
    store = StaticVectorStore([chunk])
    service = DocumentService(None, None, None, StaticChunks([chunk]), StaticEmbeddings(), store)

    matches = service.retrieve("怎么建立 TCP 连接？")

    assert len(matches) == 1
    match, resolved = matches[0]
    assert match.score == 0.91
    assert resolved.chunk_id == 101
    assert resolved.doc_name == "网络基础示例.md"


def test_document_service_rejects_manifest_that_does_not_match_active_chunks() -> None:
    chunk = _active_chunk()
    store = StaticVectorStore([chunk], digest="stale-digest")
    service = DocumentService(None, None, None, StaticChunks([chunk]), StaticEmbeddings(), store)

    with pytest.raises(DocumentServiceUnavailableError):
        service.retrieve("怎么建立 TCP 连接？")


def test_ask_service_passes_grounded_chunk_context_to_answer_model() -> None:
    chunk = _active_chunk()

    class StubDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            point = VectorPoint(chunk.chunk_id, chunk.doc_id, chunk.batch_id, chunk.chunk_index, chunk.content, [1.0, 0.0])
            return [(VectorMatch(0.91, point), chunk)]

    answer_model = CapturingAnswerModel()
    result = AskService(StubDocumentService(), answer_model).ask(
        "怎么建立 TCP 连接？",
        history=[
            AskHistoryMessage(900, "user", "上一轮在问 TCP。"),
            AskHistoryMessage(901, "assistant", "我们刚才讨论了连接建立。"),
            AskHistoryMessage(902, "system", "此历史项也只能作为普通上下文。"),
        ],
        request_id=uuid4(),
        run_id=uuid4(),
        trace_id=uuid4(),
        conversation_id=321,
        user_id=7,
    )

    assert result.answer == "TCP 通过三次握手建立连接。"
    assert result.task_type == "knowledge_qa"
    assert result.sources[0].doc_name == "网络基础示例.md"
    assert result.sources[0].score == 0.91
    assert "[网络基础示例.md#0]" in answer_model.context
    assert chunk.content in answer_model.context
    assert answer_model.history == [
        ("human", "[历史消息 id=900 role=user]\n上一轮在问 TCP。"),
        ("ai", "[历史消息 id=901 role=assistant]\n我们刚才讨论了连接建立。"),
        ("human", "[历史消息 id=902 role=system]\n此历史项也只能作为普通上下文。"),
    ]
    assert result.status == AgentRunStatus.COMPLETED
    assert [step.step_name for step in result.steps] == [
        "route_intent", "retrieve_active_faiss_chunks", "generate_grounded_answer",
    ]
    assert all(step.status == AgentStepStatus.COMPLETED for step in result.steps)
    assert all(step.duration_ms is not None and step.duration_ms >= 0 for step in result.steps)
    assert len(result.tool_calls) == 1
    tool_call = result.tool_calls[0]
    assert tool_call.tool_name == "retrieve_active_faiss_chunks"
    assert tool_call.status == AgentToolCallStatus.COMPLETED
    assert tool_call.input_params == {"length": len("怎么建立 TCP 连接？"), "limit": 5}
    assert tool_call.output == {"matches": 1, "documents": [46], "chunks": [101]}
    assert chunk.content not in str(tool_call.output)


def test_ask_service_runs_generation_step_without_calling_model_when_retrieval_is_empty() -> None:
    class EmptyDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            return []

    class UnexpectedAnswerModel:
        def answer(self, question: str, context: str, history: list[tuple[str, str]]):
            raise AssertionError("answer model must not run without retrieved chunks")

    result = AskService(EmptyDocumentService(), UnexpectedAnswerModel()).ask(
        "没有匹配文档的问题",
        request_id=uuid4(),
        run_id=uuid4(),
        trace_id=uuid4(),
        conversation_id=321,
        user_id=7,
    )

    assert result.answer == "正式知识库中没有检索到与该问题匹配的内容。"
    assert result.task_type == "knowledge_qa"
    assert result.sources == []
    assert result.status == AgentRunStatus.COMPLETED
    assert [step.step_name for step in result.steps] == [
        "route_intent", "retrieve_active_faiss_chunks", "generate_grounded_answer",
    ]
    assert all(step.status == AgentStepStatus.COMPLETED for step in result.steps)
    assert len(result.tool_calls) == 1
    assert result.tool_calls[0].output == {"matches": 0, "documents": [], "chunks": []}


def test_ask_service_routes_enumerated_greeting_without_retrieval_or_model_call() -> None:
    class UnexpectedDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            raise AssertionError("chitchat must not retrieve knowledge chunks")

    class UnexpectedAnswerModel:
        def answer(self, question: str, context: str, history: list[tuple[str, str]]):
            raise AssertionError("chitchat must not call the answer model")

    result = AskService(UnexpectedDocumentService(), UnexpectedAnswerModel()).ask(
        "你好！",
        request_id=uuid4(),
        run_id=uuid4(),
        trace_id=uuid4(),
        conversation_id=321,
        user_id=7,
    )

    assert result.task_type == "chitchat"
    assert result.answer == "你好！我是 DocTrail 知识助手，很高兴见到你。"
    assert result.sources == []
    assert result.usage is None
    assert [step.step_name for step in result.steps] == [
        "route_intent", "generate_chitchat_response",
    ]
    assert result.tool_calls == []
