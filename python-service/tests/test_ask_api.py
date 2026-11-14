from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from main import create_app
from agent.state import (
    AgentRunStatus,
    AgentStep,
    AgentStepStatus,
    AgentToolCall,
    AgentToolCallStatus,
)
from agent.orchestrator import AgentOrchestrator
from application.ask_service import AskHistoryMessage, AskResult, AskService, RetrievedSource


class StubAskService:
    def __init__(self) -> None:
        self.calls: list[tuple[str, list[AskHistoryMessage]]] = []
        self.identifiers: list[dict[str, object]] = []

    def ask(
        self,
        question: str,
        *,
        history: list[AskHistoryMessage],
        request_id,
        run_id,
        trace_id,
        conversation_id,
        user_id,
        is_admin=False,
    ) -> AskResult:
        self.calls.append((question, history))
        self.identifiers.append({
            "request_id": request_id,
            "run_id": run_id,
            "trace_id": trace_id,
            "conversation_id": conversation_id,
            "user_id": user_id,
            "is_admin": is_admin,
        })
        return AskResult(
            answer=f"根据正式知识库：{question}",
            sources=[RetrievedSource(doc_id=9, doc_name="正式文档.md", chunk_index=2, score=0.91)],
            task_type="knowledge_qa",
            status=AgentRunStatus.COMPLETED,
            steps=[
                AgentStep(uuid4(), "retrieve_active_faiss_chunks", AgentStepStatus.COMPLETED, 2),
                AgentStep(uuid4(), "generate_grounded_answer", AgentStepStatus.COMPLETED, 1),
            ],
            tool_calls=[AgentToolCall(
                uuid4(),
                "retrieve_active_faiss_chunks",
                AgentToolCallStatus.COMPLETED,
                {"length": len(question), "limit": 5},
                {"matches": 1, "documents": [9], "chunks": [101]},
                1,
            )],
            usage=None,
        )


def test_ask_requires_token_and_returns_formal_retrieval_response() -> None:
    ask_service = StubAskService()
    app = create_app(internal_token="test-token", ask_service=ask_service)
    client = TestClient(app)
    trace_id = uuid4()
    payload = {
        "question": "什么是 TCP？",
        "conversation_id": 1,
        "history": [
            {"message_id": 90, "role": "user", "content": "上一轮的问题"},
            {"message_id": 91, "role": "assistant", "content": "上一轮的回答"},
        ],
        "is_admin": False,
        "request_id": str(uuid4()),
        "run_id": str(uuid4()),
        "trace_id": str(trace_id),
        "include_trace": True,
    }

    unauthorized = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "wrong", "X-Trace-Id": str(trace_id)},
    )
    assert unauthorized.status_code == 401

    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": str(trace_id)},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["answer"].startswith("根据正式知识库")
    assert body["task_type"] == "knowledge_qa"
    assert body["run_id"] == payload["run_id"]
    assert body["trace_id"] == payload["trace_id"]
    assert body["execution_trace"]["status"] == "COMPLETED"
    assert body["sources"] == [{
        "doc_id": 9, "doc_name": "正式文档.md", "page": None, "chunk_index": 2, "score": 0.91,
    }]
    assert [step["step_name"] for step in body["execution_trace"]["steps"]] == [
        "retrieve_active_faiss_chunks", "generate_grounded_answer",
    ]
    assert body["mock"] is False
    assert all(step["status"] == "COMPLETED" for step in body["execution_trace"]["steps"])
    assert all(step["duration_ms"] >= 0 for step in body["execution_trace"]["steps"])
    assert len(body["execution_trace"]["tool_calls"]) == 1
    assert body["execution_trace"]["tool_calls"][0]["tool_name"] == "retrieve_active_faiss_chunks"
    assert ask_service.calls == [(
        "什么是 TCP？",
        [
            AskHistoryMessage(90, "user", "上一轮的问题"),
            AskHistoryMessage(91, "assistant", "上一轮的回答"),
        ],
    )]
    assert ask_service.identifiers == [{
        "request_id": UUID(payload["request_id"]),
        "run_id": UUID(payload["run_id"]),
        "trace_id": UUID(payload["trace_id"]),
        "conversation_id": payload["conversation_id"],
        "user_id": None,
        "is_admin": False,
    }]


def test_real_ask_service_runs_through_http_entry_and_returns_agent_trace() -> None:
    class EmptyDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            return []

    class UnusedAnswerModel:
        def answer(self, question: str, context: str, history: list[tuple[str, str]]):
            raise AssertionError("answer model must not run without retrieved chunks")

    class RecordingOrchestrator(AgentOrchestrator):
        def __init__(self) -> None:
            super().__init__()
            self.states = []

        def run(self, state, handlers):
            result = super().run(state, handlers)
            self.states.append(state)
            return result

    orchestrator = RecordingOrchestrator()
    ask_service = AskService(EmptyDocumentService(), UnusedAnswerModel(), orchestrator)
    client = TestClient(create_app(internal_token="test-token", ask_service=ask_service))
    request_id, run_id, trace_id = uuid4(), uuid4(), uuid4()
    payload = {
        "question": "没有匹配文档的问题",
        "conversation_id": 321,
        "user_id": 7,
        "history": [],
        "is_admin": False,
        "request_id": str(request_id),
        "run_id": str(run_id),
        "trace_id": str(trace_id),
        "include_trace": True,
    }

    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": str(trace_id)},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["run_id"] == str(run_id)
    assert body["trace_id"] == str(trace_id)
    assert body["execution_trace"]["status"] == "COMPLETED"
    assert [step["step_name"] for step in body["execution_trace"]["steps"]] == [
        "route_intent", "retrieve_active_faiss_chunks", "generate_grounded_answer",
    ]
    assert all(step["status"] == "COMPLETED" for step in body["execution_trace"]["steps"])
    assert len(body["execution_trace"]["tool_calls"]) == 1
    assert body["execution_trace"]["tool_calls"][0]["status"] == "COMPLETED"
    assert len(orchestrator.states) == 1
    state = orchestrator.states[0]
    assert (state.request_id, state.run_id, state.trace_id) == (request_id, run_id, trace_id)
    assert (state.conversation_id, state.user_id, state.original_input) == (
        321, 7, "没有匹配文档的问题",
    )


def test_real_ask_service_returns_chitchat_branch_without_sources_or_tools() -> None:
    class UnexpectedDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            raise AssertionError("chitchat must not retrieve")

    class UnexpectedAnswerModel:
        def answer(self, question: str, context: str, history: list[tuple[str, str]]):
            raise AssertionError("chitchat must not call the model")

    service = AskService(UnexpectedDocumentService(), UnexpectedAnswerModel())
    client = TestClient(create_app(internal_token="test-token", ask_service=service))
    trace_id = uuid4()
    payload = {
        "question": "你好！", "conversation_id": 321, "user_id": 7, "history": [],
        "is_admin": False, "include_trace": True, "request_id": str(uuid4()),
        "run_id": str(uuid4()), "trace_id": str(trace_id),
    }

    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": str(trace_id)},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["task_type"] == "chitchat"
    assert body["sources"] == []
    assert body["usage"] is None
    assert [step["step_name"] for step in body["execution_trace"]["steps"]] == [
        "route_intent", "generate_chitchat_response",
    ]
    assert body["execution_trace"]["tool_calls"] == []


def test_ask_returns_internal_error_when_python_token_is_not_configured() -> None:
    trace_id = str(uuid4())
    payload = {
        "question": "test",
        "conversation_id": 1,
        "history": [],
        "is_admin": False,
        "include_trace": True,
        "request_id": str(uuid4()),
        "run_id": str(uuid4()),
        "trace_id": trace_id,
    }

    response = TestClient(create_app(internal_token="")).post(
        "/api/ask",
        json=payload,
        headers={"X-Trace-Id": trace_id},
    )

    assert response.status_code == 500


def test_ask_rejects_history_over_utf8_byte_budget_before_calling_service() -> None:
    ask_service = StubAskService()
    client = TestClient(create_app(internal_token="test-token", ask_service=ask_service))
    trace_id = str(uuid4())
    payload = {
        "question": "test",
        "conversation_id": 1,
        "history": [{"message_id": 1, "role": "user", "content": "x" * 8129}],
        "is_admin": False,
        "include_trace": True,
        "request_id": str(uuid4()),
        "run_id": str(uuid4()),
        "trace_id": trace_id,
    }

    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": trace_id},
    )

    assert response.status_code == 422
    assert ask_service.calls == []


def test_ask_rejects_trace_header_mismatch() -> None:
    app = create_app(internal_token="test-token")
    client = TestClient(app)
    payload = {
        "question": "test", "conversation_id": 1, "history": [], "is_admin": False,
        "include_trace": True, "request_id": str(uuid4()), "run_id": str(uuid4()),
        "trace_id": str(uuid4()),
    }
    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": str(uuid4())},
    )
    assert response.status_code == 400


def test_ask_request_rejects_missing_required_contract_fields() -> None:
    app = create_app(internal_token="test-token")
    client = TestClient(app)
    payload = {
        "question": "test", "conversation_id": 1, "history": [],
        "request_id": str(uuid4()), "run_id": str(uuid4()), "trace_id": str(uuid4()),
    }
    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": payload["trace_id"]},
    )
    assert response.status_code == 422


def test_ask_request_rejects_non_positive_conversation_id() -> None:
    app = create_app(internal_token="test-token")
    client = TestClient(app)
    trace_id = str(uuid4())
    payload = {
        "question": "test", "conversation_id": 0, "history": [], "is_admin": False,
        "include_trace": True, "request_id": str(uuid4()), "run_id": str(uuid4()),
        "trace_id": trace_id,
    }
    response = client.post(
        "/api/ask",
        json=payload,
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": trace_id},
    )
    assert response.status_code == 422
