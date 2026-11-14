from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from agent.state import AgentRunStatus, AgentState
from application.ask_service import AskService
from application.run_service import AgentRunIdentityConflictError, AgentRunService
from main import create_app


class UnusedAnswerModel:
    def answer(self, question: str, context: str, history: list[tuple[str, str]]):
        raise AssertionError("answer model must not run without retrieved chunks")


def _payload():
    return {
        "question": "没有匹配文档的问题",
        "conversation_id": 321,
        "user_id": 7,
        "history": [],
        "is_admin": False,
        "request_id": str(uuid4()),
        "run_id": str(uuid4()),
        "trace_id": str(uuid4()),
        "include_trace": True,
    }


def _headers(payload: dict[str, object], token: str = "test-token") -> dict[str, str]:
    return {
        "X-Internal-Token": token,
        "X-Trace-Id": str(payload["trace_id"]),
    }


def test_completed_ask_can_be_read_through_all_diagnostic_routes() -> None:
    class EmptyDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            return []

    service = AskService(EmptyDocumentService(), UnusedAnswerModel())
    client = TestClient(create_app(internal_token="test-token", ask_service=service))
    payload = _payload()

    ask_response = client.post("/api/ask", json=payload, headers=_headers(payload))
    summary = client.get(
        f"/api/agent/run/{payload['run_id']}",
        headers={"X-Internal-Token": "test-token"},
    )
    steps = client.get(
        f"/api/agent/run/{payload['run_id']}/steps",
        headers={"X-Internal-Token": "test-token"},
    )
    tool_calls = client.get(
        f"/api/agent/run/{payload['run_id']}/tool-calls",
        headers={"X-Internal-Token": "test-token"},
    )

    assert ask_response.status_code == 200
    assert summary.status_code == steps.status_code == tool_calls.status_code == 200
    assert summary.json() == {
        "request_id": payload["request_id"],
        "run_id": payload["run_id"],
        "trace_id": payload["trace_id"],
        "conversation_id": 321,
        "user_id": 7,
        "task_type": "knowledge_qa",
        "status": "COMPLETED",
    }
    assert [step["step_name"] for step in steps.json()["steps"]] == [
        "route_intent",
        "retrieve_active_faiss_chunks",
        "generate_grounded_answer",
    ]
    assert tool_calls.json()["tool_calls"][0]["input_params"] == {
        "length": len(payload["question"]),
        "limit": 5,
    }
    assert "没有匹配文档的问题" not in str(tool_calls.json())


def test_failed_ask_keeps_safe_failed_diagnostics() -> None:
    class FailingDocumentService:
        def retrieve(self, question: str, *, top_k: int):
            raise RuntimeError("secret backend detail")

    service = AskService(FailingDocumentService(), UnusedAnswerModel())
    client = TestClient(create_app(internal_token="test-token", ask_service=service))
    payload = _payload()

    ask_response = client.post("/api/ask", json=payload, headers=_headers(payload))
    summary = client.get(
        f"/api/agent/run/{payload['run_id']}",
        headers={"X-Internal-Token": "test-token"},
    )
    steps = client.get(
        f"/api/agent/run/{payload['run_id']}/steps",
        headers={"X-Internal-Token": "test-token"},
    )
    tool_calls = client.get(
        f"/api/agent/run/{payload['run_id']}/tool-calls",
        headers={"X-Internal-Token": "test-token"},
    )

    assert ask_response.status_code == 502
    assert summary.json()["status"] == "FAILED"
    assert steps.json()["steps"][0]["step_name"] == "route_intent"
    assert steps.json()["steps"][0]["status"] == "COMPLETED"
    assert steps.json()["steps"][1]["step_name"] == "retrieve_active_faiss_chunks"
    assert steps.json()["steps"][1]["status"] == "FAILED"
    assert steps.json()["steps"][1]["error_message"] == "Agent step execution failed"
    assert tool_calls.json()["tool_calls"][0]["status"] == "FAILED"
    assert tool_calls.json()["tool_calls"][0]["error_message"] == "Tool execution failed"
    assert "secret backend detail" not in str(steps.json())
    assert "secret backend detail" not in str(tool_calls.json())


def test_diagnostic_routes_require_token_and_return_404_for_unknown_run() -> None:
    client = TestClient(create_app(internal_token="test-token"))
    run_id = uuid4()

    unauthorized = client.get(f"/api/agent/run/{run_id}")
    missing = client.get(
        f"/api/agent/run/{run_id}",
        headers={"X-Internal-Token": "test-token"},
    )
    invalid_run_id = client.get(
        "/api/agent/run/not-a-uuid",
        headers={"X-Internal-Token": "test-token"},
    )
    unconfigured = TestClient(create_app(internal_token="")).get(
        f"/api/agent/run/{run_id}",
    )

    assert unauthorized.status_code == 401
    assert missing.status_code == 404
    assert missing.json() == {"detail": "Agent run not found in this process"}
    assert invalid_run_id.status_code == 422
    assert invalid_run_id.json() == {"detail": "Request validation failed"}
    assert unconfigured.status_code == 500


def test_run_service_returns_snapshots_and_rejects_identity_overwrite() -> None:
    runs = AgentRunService()
    state = AgentState(
        request_id=uuid4(),
        run_id=uuid4(),
        trace_id=uuid4(),
        conversation_id=1,
        user_id=7,
        original_input="sensitive question",
    )
    runs.save(state)
    state.status = AgentRunStatus.COMPLETED

    stored = runs.find(state.run_id)
    assert stored is not None
    assert stored.status == AgentRunStatus.RUNNING
    stored.status = AgentRunStatus.FAILED
    assert runs.find(state.run_id).status == AgentRunStatus.RUNNING

    conflicting = AgentState(
        request_id=uuid4(),
        run_id=state.run_id,
        trace_id=uuid4(),
        conversation_id=1,
        user_id=7,
        original_input="other question",
    )
    with pytest.raises(AgentRunIdentityConflictError):
        runs.save(conflicting)
