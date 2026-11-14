"""Minimal dependency-light validation for the checked-in HTTP contracts."""

from __future__ import annotations

import json
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts"
EXAMPLES = CONTRACTS / "examples"

PUBLIC_PATHS = {
    "/health/live", "/auth/login", "/auth/register", "/auth/refresh", "/auth/logout",
    "/admin/auth/login", "/chat/conversations", "/chat/conversations/{conversationId}",
    "/chat/messages", "/chat/stream/messages", "/knowledge/documents",
    "/admin/knowledge/documents", "/admin/knowledge/documents/{docId}",
    "/admin/dashboard", "/admin/agent-runs", "/admin/agent-runs/{runId}",
}
INTERNAL_PATHS = {
    "/health/live", "/health/ready", "/api/ask", "/api/parse", "/api/delete",
    "/api/vector-store/stats", "/api/vector-store/rebuild",
    "/api/agent/run/{run_id}", "/api/agent/run/{run_id}/steps",
    "/api/agent/run/{run_id}/tool-calls",
}
REQUIRED_EXAMPLES = {
    "java-health-live.response.json", "auth-login.request.json", "auth-login.response.json", "chat-message.response.json",
    "chat-message-chitchat.response.json", "ai-ask.request.json", "ai-ask.response.json",
    "ai-ask-chitchat.response.json", "ai-parse.request.json",
    "ai-parse.response.json", "ai-delete.request.json", "ai-delete.response.json",
    "ai-vector-store-stats.response.json", "ai-vector-store-rebuild.response.json",
    "ai-agent-run.response.json", "ai-agent-run-steps.response.json",
    "ai-agent-run-tool-calls.response.json",
}


def load_yaml(name: str) -> dict:
    value = yaml.safe_load((CONTRACTS / name).read_text(encoding="utf-8"))
    assert isinstance(value, dict), f"{name} must contain an object"
    assert value.get("openapi") == "3.1.0", f"{name} must use OpenAPI 3.1.0"
    assert isinstance(value.get("paths"), dict), f"{name} must define paths"
    return value


def validate_operations(document: dict, name: str) -> None:
    operation_ids: list[str] = []
    for path, path_item in document["paths"].items():
        assert isinstance(path_item, dict), f"{name}:{path} must be an object"
        for method, operation in path_item.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                continue
            assert isinstance(operation, dict), f"{name}:{method} {path} must be an object"
            operation_id = operation.get("operationId")
            assert isinstance(operation_id, str) and operation_id, f"missing operationId: {name}:{method} {path}"
            assert isinstance(operation.get("responses"), dict) and operation["responses"], f"missing responses: {operation_id}"
            operation_ids.append(operation_id)
    assert len(operation_ids) == len(set(operation_ids)), f"duplicate operationId in {name}"


def load_examples() -> dict[str, object]:
    examples: dict[str, object] = {}
    for path in EXAMPLES.glob("*.json"):
        examples[path.name] = json.loads(path.read_text(encoding="utf-8"))
    missing = REQUIRED_EXAMPLES - examples.keys()
    assert not missing, f"missing examples: {sorted(missing)}"
    return examples


def main() -> None:
    public = load_yaml("public-openapi.yaml")
    internal = load_yaml("ai-openapi.yaml")
    sse = yaml.safe_load((CONTRACTS / "sse-events.yaml").read_text(encoding="utf-8"))
    assert isinstance(sse, dict) and sse.get("version") == "1.0.0", "invalid sse-events.yaml"
    assert PUBLIC_PATHS <= set(public["paths"]), f"missing public paths: {sorted(PUBLIC_PATHS - public['paths'].keys())}"
    assert INTERNAL_PATHS <= set(internal["paths"]), f"missing internal paths: {sorted(INTERNAL_PATHS - internal['paths'].keys())}"
    validate_operations(public, "public-openapi.yaml")
    validate_operations(internal, "ai-openapi.yaml")
    message_schema = public["components"]["schemas"]["Message"]
    assert "sources" in message_schema.get("required", []), "Message.sources must be required"
    assert message_schema["properties"]["sources"]["items"]["$ref"] == "#/components/schemas/Source"

    examples = load_examples()
    java_liveness = examples["java-health-live.response.json"]
    assert isinstance(java_liveness, dict) and set(java_liveness) == {"status", "service", "timestamp"}
    assert java_liveness["status"] == "UP"
    assert java_liveness["service"] == "doctrail-java"
    assert isinstance(java_liveness["timestamp"], str) and java_liveness["timestamp"].endswith("Z")
    ask_request = examples["ai-ask.request.json"]
    ask_response = examples["ai-ask.response.json"]
    chat_response = examples["chat-message.response.json"]
    chitchat_ask_response = examples["ai-ask-chitchat.response.json"]
    chitchat_chat_response = examples["chat-message-chitchat.response.json"]
    run_response = examples["ai-agent-run.response.json"]
    run_steps = examples["ai-agent-run-steps.response.json"]
    run_tool_calls = examples["ai-agent-run-tool-calls.response.json"]
    assert isinstance(ask_request, dict) and {"question", "conversation_id", "request_id", "run_id", "trace_id"} <= ask_request.keys()
    assert isinstance(ask_response, dict) and {"answer", "sources", "run_id", "trace_id", "execution_trace", "mock"} <= ask_response.keys()
    assert ask_response["run_id"] == ask_request["run_id"]
    assert ask_response["trace_id"] == ask_request["trace_id"]
    assert ask_response["mock"] is False
    assert isinstance(chat_response, dict) and chat_response.get("data", {}).get("mock") is False
    assert chitchat_ask_response.get("task_type") == "chitchat"
    assert chitchat_ask_response.get("sources") == []
    assert chitchat_ask_response.get("execution_trace", {}).get("tool_calls") == []
    assert chitchat_chat_response.get("data", {}).get("taskType") == "chitchat"
    assert isinstance(run_response, dict) and run_response.get("run_id") == ask_response["run_id"]
    assert isinstance(run_steps, dict) and run_steps.get("run_id") == ask_response["run_id"]
    assert isinstance(run_tool_calls, dict) and run_tool_calls.get("run_id") == ask_response["run_id"]
    assert run_steps.get("steps") == ask_response["execution_trace"]["steps"]
    assert run_tool_calls.get("tool_calls") == ask_response["execution_trace"]["tool_calls"]
    print("contract validation passed: YAML/OpenAPI, JSON examples, and interface contract checks")


if __name__ == "__main__":
    main()
