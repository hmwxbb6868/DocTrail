from __future__ import annotations

from time import sleep
from uuid import uuid4

import pytest
from pydantic import BaseModel, ConfigDict

from agent.events import AgentEventBus
from agent.state import AgentState, AgentToolCallStatus
from tools.base import Tool, ToolMetadata, ToolPermission
from tools.registry import (
    ToolPermissionDeniedError,
    ToolRegistry,
    ToolSchemaError,
    ToolTimeoutError,
)


class ToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    value: str


class ToolOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    result: str


class FakeTool(Tool):
    name = "test_tool"
    description = "test"
    input_model = ToolInput
    output_model = ToolOutput

    def __init__(self, outcomes, *, timeout_ms=100, max_retries=0, permission=ToolPermission.USER):
        self.outcomes = list(outcomes)
        self.calls = 0
        self.metadata = ToolMetadata(timeout_ms, max_retries, 0, permission)

    def execute(self, parameters: BaseModel) -> BaseModel:
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        if callable(outcome):
            return outcome()
        return outcome

    def is_retryable(self, error: Exception) -> bool:
        return isinstance(error, ConnectionError)


def _events() -> tuple[AgentState, AgentEventBus]:
    state = AgentState(uuid4(), uuid4(), uuid4(), 1, 7, "question")
    return state, AgentEventBus(state)


def test_registry_validates_schema_and_records_completed_call() -> None:
    state, events = _events()
    registry = ToolRegistry()
    tool = FakeTool([ToolOutput(result="ok")])
    registry.register(tool)

    output = registry.invoke(
        "test_tool", {"value": "input"}, caller_permission=ToolPermission.USER, event_bus=events,
    )

    assert output == ToolOutput(result="ok")
    assert len(state.tool_calls) == 1
    assert state.tool_calls[0].status == AgentToolCallStatus.COMPLETED
    assert state.tool_calls[0].input_params == {"value": "input"}
    assert state.tool_calls[0].output == {"result": "ok"}


def test_registry_retries_only_declared_retryable_error_under_same_call() -> None:
    state, events = _events()
    registry = ToolRegistry()
    tool = FakeTool([ConnectionError("temporary"), ToolOutput(result="recovered")], max_retries=1)
    registry.register(tool)

    output = registry.invoke(
        "test_tool", {"value": "input"}, caller_permission=ToolPermission.USER, event_bus=events,
    )

    assert output.result == "recovered"
    assert tool.calls == 2
    assert len(state.tool_calls) == 1
    assert state.tool_calls[0].status == AgentToolCallStatus.COMPLETED


def test_registry_rejects_invalid_input_and_admin_tool_for_user() -> None:
    state, events = _events()
    registry = ToolRegistry()
    registry.register(FakeTool([ToolOutput(result="unused")], permission=ToolPermission.ADMIN))

    with pytest.raises(ToolPermissionDeniedError):
        registry.invoke(
            "test_tool", {"value": "input"}, caller_permission=ToolPermission.USER, event_bus=events,
        )
    assert state.tool_calls == []

    user_registry = ToolRegistry()
    user_registry.register(FakeTool([ToolOutput(result="unused")]))
    with pytest.raises(ToolSchemaError):
        user_registry.invoke(
            "test_tool", {"unexpected": "input"}, caller_permission=ToolPermission.USER, event_bus=events,
        )


def test_registry_timeout_returns_without_accepting_late_result() -> None:
    state, events = _events()
    registry = ToolRegistry()

    def slow_output():
        sleep(0.05)
        return ToolOutput(result="late")

    registry.register(FakeTool([slow_output], timeout_ms=5))

    with pytest.raises(ToolTimeoutError):
        registry.invoke(
            "test_tool", {"value": "input"}, caller_permission=ToolPermission.USER, event_bus=events,
        )

    assert len(state.tool_calls) == 1
    assert state.tool_calls[0].status == AgentToolCallStatus.FAILED
    assert state.tool_calls[0].output is None
    assert state.tool_calls[0].error_code == 3002
    assert state.tool_calls[0].error_message == "Tool execution timed out"
