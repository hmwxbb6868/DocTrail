from __future__ import annotations

from time import perf_counter
from typing import Callable, Mapping

from agent.events import AgentEvent, AgentEventBus, AgentEventType
from agent.state import PlannedStep


class AgentExecutor:
    """Executes planned steps in order and emits their terminal state exactly once."""

    def execute(
        self,
        steps: tuple[PlannedStep, ...],
        handlers: Mapping[str, Callable[[AgentEventBus], None]],
        event_bus: AgentEventBus,
    ) -> None:
        # 循环遍历每一个 PlannedStep（规划器生成的计划步骤）
        for step in steps:
            # 根据step_name，从handlers字典取出对应的业务处理函数
            handler = handlers.get(step.step_name)
            if handler is None:
                raise ValueError(f"missing handler for Agent step: {step.step_name}")
            # 【1】发布步骤开始事件 STEP_STARTED
            event_bus.publish(AgentEvent(
                AgentEventType.STEP_STARTED,
                step.step_id,
                step.step_name,
            ))
            # 记录当前时间，用来计算耗时
            started = perf_counter()
            try:
                # 执行业务handler（检索、LLM生成等业务逻辑）
                handler(event_bus) #让业务逻辑（handler 内部）在执行过程中，可以主动发布【工具事件 AgentToolEvent】
            except Exception:
                # 捕获到异常：发布 STEP_FAILED 事件
                event_bus.publish(AgentEvent(
                    AgentEventType.STEP_FAILED,
                    step.step_id,
                    step.step_name,
                    duration_ms=max(0, int((perf_counter() - started) * 1000)),
                    error_message="Agent step execution failed",
                ))
                # 继续向上抛出异常，让外层Orchestrator感知任务失败
                raise
            # 无异常，执行成功：发布 STEP_COMPLETED 事件
            event_bus.publish(AgentEvent(
                AgentEventType.STEP_COMPLETED,
                step.step_id,
                step.step_name,
                duration_ms=max(0, int((perf_counter() - started) * 1000)),
            ))
