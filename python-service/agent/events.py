from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Callable
from uuid import UUID

from agent.state import (
    AgentState,
    AgentStep,
    AgentStepStatus,
    AgentToolCall,
    AgentToolCallStatus,
)

#事件总线
#收到事件（STEP_STARTED / STEP_COMPLETED / STEP_FAILED），自动更新 AgentState 里面 AgentStep 的状态
class AgentEventType(str, Enum):
    STEP_STARTED = "STEP_STARTED" #步骤开始执行，触发 EventBus：新建`AgentStep`放进`state.steps`。
    STEP_COMPLETED = "STEP_COMPLETED" #步骤完成执行，触发 EventBus：更新`AgentStep`状态为`COMPLETED`。
    STEP_FAILED = "STEP_FAILED" #步骤执行失败，触发 EventBus：更新`AgentStep`状态为`FAILED`。


class AgentToolEventType(str, Enum):
    TOOL_STARTED = "TOOL_STARTED"
    TOOL_COMPLETED = "TOOL_COMPLETED"
    TOOL_FAILED = "TOOL_FAILED"

#事件消息载体（事件数据包），来封装「步骤生命周期发生变化」这一条消息里的全部信息
@dataclass(frozen=True)
class AgentEvent:
    event_type: AgentEventType
    step_id: UUID
    step_name: str
    duration_ms: int | None = None
    error_code: int | None = None
    error_message: str | None = None


@dataclass(frozen=True)
class AgentToolEvent:
    event_type: AgentToolEventType
    tool_call_id: UUID
    tool_name: str
    input_params: dict | None = None
    output: dict | None = None
    duration_ms: int | None = None
    error_code: int | None = None
    error_message: str | None = None


class AgentEventBus:
    """Publishes execution events and projects them into one request's state."""
#发布执行事件，并且把事件数据同步映射到本次请求对应的 AgentState 中

    def __init__(self, state: AgentState) -> None:
        self._state = state
        #list[Callable[[AgentEvent | AgentToolEvent], None]]：一个列表，列表里每一项都是函数；该函数接收 AgentEvent或者一个AgentToolEvent 作为参数，返回 None。
        self._subscribers: list[Callable[[AgentEvent | AgentToolEvent], None]] = []

    #事件总线的注册订阅
    #Callable[[AgentEvent | AgentToolEvent], None]：必须接收 1 个 AgentEvent或者一个AgentToolEvent 对象作为参数，并且没有返回值
    def subscribe(self, subscriber: Callable[[AgentEvent | AgentToolEvent], None]) -> None:
        #把传进来的这个回调函数，**追加到订阅者花名册列表里保存起来**。
        self._subscribers.append(subscriber)

    #事件总线的发布事件
    def publish(self, event: AgentEvent | AgentToolEvent) -> None:
        #优先更新 AgentState 里面的步骤状态
        if isinstance(event, AgentToolEvent):
            self._apply_tool_to_state(event)
        else:
            self._apply_step_to_state(event)
        #遍历订阅者花名册列表，对列表里的每一个订阅者
        for subscriber in tuple(self._subscribers):
            #把AgentEvent 消息对象传给每一个订阅回调函数
            subscriber(event)

    #把事件数据同步映射到 AgentState 中
    def _apply_step_to_state(self, event: AgentEvent) -> None:
        # 根据事件里的step_id，去AgentState的steps列表查找对应的AgentStep
        step = self._state.find_step(event.step_id)
        # 分支1：事件类型是 STEP_STARTED（步骤开始执行）
        if event.event_type == AgentEventType.STEP_STARTED:
            # 如果已经存在这个step_id，代表重复启动步骤，抛异常
            if step is not None:
                raise ValueError(f"duplicate Agent step id: {event.step_id}")
            # 新建AgentStep实例，追加到state.steps列表
            self._state.steps.append(AgentStep(event.step_id, event.step_name))
            return

        # 走到这里，代表事件是 STEP_COMPLETED / STEP_FAILED
        # 校验：必须找到这个step，并且step名称要匹配，否则报错
        if step is None or step.step_name != event.step_name:
            raise ValueError(f"unknown Agent step id: {event.step_id}")
        # 将事件携带的耗时、错误信息写入AgentStep对象
        step.duration_ms = event.duration_ms
        step.error_code = event.error_code
        step.error_message = event.error_message
        # 根据事件类型，设置步骤状态
        step.status = (
            AgentStepStatus.COMPLETED
            if event.event_type == AgentEventType.STEP_COMPLETED
            else AgentStepStatus.FAILED
        )

    #把事件数据同步映射到 AgentState 中
    def _apply_tool_to_state(self, event: AgentToolEvent) -> None:
        tool_call = self._state.find_tool_call(event.tool_call_id)
        if event.event_type == AgentToolEventType.TOOL_STARTED:
            # 如果已经存在这个tool_call_id，代表重复启动工具调用，抛异常
            if tool_call is not None:
                raise ValueError(f"duplicate Agent tool call id: {event.tool_call_id}")
            self._state.tool_calls.append(AgentToolCall(
                tool_call_id=event.tool_call_id,
                tool_name=event.tool_name,
                input_params=event.input_params,
            ))
            return
        if tool_call is None or tool_call.tool_name != event.tool_name:
            raise ValueError(f"unknown Agent tool call id: {event.tool_call_id}")
        tool_call.output = event.output
        tool_call.duration_ms = event.duration_ms
        tool_call.error_code = event.error_code
        tool_call.error_message = event.error_message
        tool_call.status = (
            AgentToolCallStatus.COMPLETED
            if event.event_type == AgentToolEventType.TOOL_COMPLETED
            else AgentToolCallStatus.FAILED
        )
