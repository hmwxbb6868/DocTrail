from uuid import uuid4

import pytest

from agent.events import AgentEvent, AgentEventBus, AgentEventType
from agent.orchestrator import AgentOrchestrator
from agent.planner import AgentPlanner
from agent.state import AgentRunStatus, AgentState, AgentStepStatus


def _state() -> AgentState:
    return AgentState(
        request_id=uuid4(),
        run_id=uuid4(),
        trace_id=uuid4(),
        conversation_id=321,
        user_id=7,
        original_input="解释 TCP 三次握手",
    )


def test_planner_and_orchestrator_run_knowledge_qa_steps_in_order() -> None:
    state = _state()
    order: list[str] = []
    planned = AgentPlanner().plan(state)

    assert [step.step_name for step in planned] == [
        "retrieve_active_faiss_chunks",
        "generate_grounded_answer",
    ]
    result = AgentOrchestrator().run(state, {
        "retrieve_active_faiss_chunks": lambda _events: order.append("retrieve"),
        "generate_grounded_answer": lambda _events: order.append("generate"),
    })

    assert result is state
    assert order == ["retrieve", "generate"]
    assert state.status == AgentRunStatus.COMPLETED
    assert [step.step_name for step in state.steps] == [
        "route_intent",
        "retrieve_active_faiss_chunks",
        "generate_grounded_answer",
    ]
    assert [step.status for step in state.steps] == [
        AgentStepStatus.COMPLETED,
        AgentStepStatus.COMPLETED,
        AgentStepStatus.COMPLETED,
    ]
    assert all(step.duration_ms is not None and step.duration_ms >= 0 for step in state.steps)


def test_executor_marks_failed_step_and_run_before_reraising() -> None:
    state = _state()

    def fail_generation() -> None:
        raise RuntimeError("provider failure")

    with pytest.raises(RuntimeError, match="provider failure"):
        AgentOrchestrator().run(state, {
            "retrieve_active_faiss_chunks": lambda _events: None,
            "generate_grounded_answer": lambda _events: fail_generation(),
        })

    assert state.status == AgentRunStatus.FAILED
    assert [step.status for step in state.steps] == [
        AgentStepStatus.COMPLETED,
        AgentStepStatus.COMPLETED,
        AgentStepStatus.FAILED,
    ]
    assert state.steps[-1].error_message == "Agent step execution failed"
    assert state.steps[-1].duration_ms is not None


def test_event_bus_rejects_duplicate_step_start_event() -> None:
    state = _state()
    event_bus = AgentEventBus(state)
    step_id = uuid4()
    event = AgentEvent(AgentEventType.STEP_STARTED, step_id, "retrieve_active_faiss_chunks")

    event_bus.publish(event)
    with pytest.raises(ValueError, match="duplicate Agent step id"):
        event_bus.publish(event)
