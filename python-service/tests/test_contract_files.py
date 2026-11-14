import json
from pathlib import Path

import pytest
import yaml
from pydantic import ValidationError

from main import (
    AgentRunDiagnosticResponse,
    AgentRunStepsResponse,
    AgentRunToolCallsResponse,
    AskRequest,
    AskResponse,
    DeleteRequest,
    DeleteResponse,
    ParseRequest,
    ParseResponse,
    VectorStoreStatsResponse,
)


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"


def test_stage_one_contract_documents_and_operations_are_present() -> None:
    public = yaml.safe_load((CONTRACTS / "public-openapi.yaml").read_text(encoding="utf-8"))
    internal = yaml.safe_load((CONTRACTS / "ai-openapi.yaml").read_text(encoding="utf-8"))
    sse = yaml.safe_load((CONTRACTS / "sse-events.yaml").read_text(encoding="utf-8"))

    assert public["openapi"] == "3.1.0"
    assert internal["openapi"] == "3.1.0"
    assert sse["version"] == "1.0.0"
    assert {
        "/health/live", "/auth/login", "/auth/register", "/auth/refresh", "/auth/logout",
        "/admin/auth/login", "/chat/conversations", "/chat/conversations/{conversationId}",
        "/chat/messages", "/chat/stream/messages", "/knowledge/documents",
        "/admin/knowledge/documents", "/admin/knowledge/documents/{docId}",
        "/admin/dashboard", "/admin/agent-runs", "/admin/agent-runs/{runId}",
    }.issubset(public["paths"])
    assert {
        "/health/live", "/health/ready", "/api/ask", "/api/parse", "/api/delete",
        "/api/vector-store/stats", "/api/vector-store/rebuild",
        "/api/agent/run/{run_id}", "/api/agent/run/{run_id}/steps",
        "/api/agent/run/{run_id}/tool-calls",
    }.issubset(internal["paths"])
    history_schema = internal["components"]["schemas"]["AskRequest"]["properties"]["history"]
    assert history_schema["maxItems"] == 20
    assert "8192" in history_schema["description"]
    assert "UTF-8" in history_schema["description"]

    for document in (public, internal):
        operation_ids = []
        for path_item in document["paths"].values():
            for method, operation in path_item.items():
                if method.lower() in {"get", "post", "put", "patch", "delete"}:
                    assert operation.get("operationId")
                    assert operation.get("responses")
                    operation_ids.append(operation["operationId"])
        assert len(operation_ids) == len(set(operation_ids))


def test_required_examples_parse_and_internal_ask_matches_pydantic() -> None:
    required_examples = {
        "java-health-live.response.json", "auth-login.request.json", "auth-login.response.json", "chat-message.response.json",
        "chat-message-chitchat.response.json", "ai-ask.request.json", "ai-ask.response.json",
        "ai-ask-chitchat.response.json",
        "ai-parse.request.json", "ai-parse.response.json",
        "ai-delete.request.json", "ai-delete.response.json",
        "ai-vector-store-stats.response.json",
        "ai-vector-store-rebuild.response.json",
        "ai-agent-run.response.json", "ai-agent-run-steps.response.json",
        "ai-agent-run-tool-calls.response.json",
    }
    examples = {
        path.name: json.loads(path.read_text(encoding="utf-8"))
        for path in (CONTRACTS / "examples").glob("*.json")
    }
    assert required_examples.issubset(examples)

    java_liveness = examples["java-health-live.response.json"]
    assert java_liveness["status"] == "UP"
    assert java_liveness["service"] == "doctrail-java"

    login_request = examples["auth-login.request.json"]
    assert set(login_request) == {"username", "password"}
    login_response = examples["auth-login.response.json"]
    assert login_response["data"]["mock"] is True
    assert set(login_response["data"]) == {"accessToken", "mock"}
    assert examples["chat-message.response.json"]["data"]["mock"] is False

    request = AskRequest.model_validate(examples["ai-ask.request.json"])
    response = AskResponse.model_validate(examples["ai-ask.response.json"])
    chitchat_response = AskResponse.model_validate(examples["ai-ask-chitchat.response.json"])
    assert request.conversation_id > 0
    assert response.mock is False
    assert response.run_id == request.run_id
    assert response.trace_id == request.trace_id
    assert chitchat_response.task_type == "chitchat"
    assert chitchat_response.sources == []
    assert chitchat_response.execution_trace.tool_calls == []
    assert examples["chat-message-chitchat.response.json"]["data"]["taskType"] == "chitchat"

    parse_request = ParseRequest.model_validate(examples["ai-parse.request.json"])
    parse_response = ParseResponse.model_validate(examples["ai-parse.response.json"])
    assert ".." not in parse_request.storage_key
    assert parse_response.status == "COMPLETED"
    assert parse_response.doc_id == parse_request.doc_id
    assert parse_response.trace_id == parse_request.trace_id

    delete_request = DeleteRequest.model_validate(examples["ai-delete.request.json"])
    delete_response = DeleteResponse.model_validate(examples["ai-delete.response.json"])
    assert delete_response.status == "DELETED"
    assert delete_response.doc_id == delete_request.doc_id
    assert delete_response.trace_id == delete_request.trace_id

    stats_response = VectorStoreStatsResponse.model_validate(examples["ai-vector-store-stats.response.json"])
    assert stats_response.backend == "faiss"
    assert stats_response.ready is True

    rebuild_response = VectorStoreStatsResponse.model_validate(
        examples["ai-vector-store-rebuild.response.json"]
    )
    assert rebuild_response.backend == "faiss"
    assert rebuild_response.ready is True

    run_response = AgentRunDiagnosticResponse.model_validate(
        examples["ai-agent-run.response.json"]
    )
    run_steps = AgentRunStepsResponse.model_validate(
        examples["ai-agent-run-steps.response.json"]
    )
    run_tool_calls = AgentRunToolCallsResponse.model_validate(
        examples["ai-agent-run-tool-calls.response.json"]
    )
    assert run_response.run_id == response.run_id
    assert run_steps.run_id == response.run_id
    assert run_tool_calls.run_id == response.run_id
    assert run_steps.steps == response.execution_trace.steps
    assert run_tool_calls.tool_calls == response.execution_trace.tool_calls


def test_usage_estimated_cost_is_required_but_may_be_null() -> None:
    from main import AskUsage

    usage = AskUsage.model_validate({
        "prompt_tokens": 10,
        "completion_tokens": 20,
        "total_tokens": 30,
        "estimated_cost": None,
    })
    assert usage.estimated_cost is None

    with pytest.raises(ValidationError):
        AskUsage.model_validate({
            "prompt_tokens": 10,
            "completion_tokens": 20,
            "total_tokens": 30,
        })


def test_ask_history_budget_accepts_exact_limit_and_rejects_one_byte_over() -> None:
    base = {
        "question": "test",
        "conversation_id": 1,
        "history": [],
        "is_admin": False,
        "include_trace": True,
        "request_id": "8cc63b60-29fa-44b5-91bf-aa29b8ad0ab1",
        "run_id": "882b535f-36fe-4ab7-8468-e229674efa47",
        "trace_id": "15fcdb04-b282-4c39-a51d-4fb7f948b43c",
    }
    exact_limit = {**base, "history": [{"message_id": 1, "role": "user", "content": "x" * 8128}]}
    one_byte_over = {**base, "history": [{"message_id": 1, "role": "user", "content": "x" * 8129}]}

    assert AskRequest.model_validate(exact_limit).history[0].content == "x" * 8128
    with pytest.raises(ValidationError):
        AskRequest.model_validate(one_byte_over)

    too_many_messages = {
        **base,
        "history": [
            {"message_id": index + 1, "role": "user", "content": "x"}
            for index in range(21)
        ],
    }
    with pytest.raises(ValidationError):
        AskRequest.model_validate(too_many_messages)


def test_storage_key_rejects_absolute_and_parent_paths() -> None:
    base = {
        "doc_id": 46,
        "content_hash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "force": False,
        "trace_id": "15fcdb04-b282-4c39-a51d-4fb7f948b43c",
    }
    for storage_key in ("/data/uploads/file.pdf", "C:/uploads/file.pdf", "2026/../file.pdf"):
        with pytest.raises(ValidationError):
            ParseRequest.model_validate({**base, "storage_key": storage_key})
