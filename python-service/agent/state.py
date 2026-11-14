from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from uuid import UUID

#整个 Agent 任务的全局运行状态
class AgentRunStatus(str, Enum):
    RUNNING = "RUNNING"# 整个Agent任务正在跑
    COMPLETED = "COMPLETED"# 整个Agent任务完成
    FAILED = "FAILED"# 整个Agent任务失败
    CANCELLED = "CANCELLED"# 整个Agent任务取消

#单个步骤的状态
class AgentStepStatus(str, Enum):
    RUNNING = "RUNNING" # 该步骤正在执行
    COMPLETED = "COMPLETED" # 该步骤完成执行
    FAILED = "FAILED" # 该步骤执行失败
    CANCELLED = "CANCELLED" # 该步骤执行取消

#单次工具调用的生命周期状态
class AgentToolCallStatus(str, Enum):
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

#规划出来的静态计划步骤
@dataclass(frozen=True)
class PlannedStep:
    step_id: UUID# 步骤的唯一标识
    step_name: str# 步骤的名称

#单个步骤实际执行记录
@dataclass
class AgentStep:
    step_id: UUID
    step_name: str
    status: AgentStepStatus = AgentStepStatus.RUNNING #单步状态，默认`RUNNING`
    duration_ms: int | None = None
    error_code: int | None = None
    error_message: str | None = None

@dataclass
class AgentToolCall:
    tool_call_id: UUID
    tool_name: str
    status: AgentToolCallStatus = AgentToolCallStatus.RUNNING
    input_params: dict | None = None #入参（脱敏审计后的参数，不是原始完整参数），TOOL_STARTED 事件写入
    output: dict | None = None #工具执行返回结果（审计后）
    duration_ms: int | None = None
    error_code: int | None = None
    error_message: str | None = None


@dataclass
class AgentState:
    request_id: UUID
    run_id: UUID
    trace_id: UUID
    conversation_id: int
    user_id: int | None
    original_input: str #用户原始提问文本
    is_admin: bool = False
    task_type: str = "knowledge_qa" #默认知识问答
    route_key: str | None = None
    status: AgentRunStatus = AgentRunStatus.RUNNING
    steps: list[AgentStep] = field(default_factory=list)
    tool_calls: list[AgentToolCall] = field(default_factory=list)

    def find_step(self, step_id: UUID) -> AgentStep | None:
        #在 self.steps（AgentStep 列表）里，按 step_id 查找对应的 AgentStep 对象
        return next((step for step in self.steps if step.step_id == step_id), None)

    def find_tool_call(self, tool_call_id: UUID) -> AgentToolCall | None:
        return next(
            (tool_call for tool_call in self.tool_calls if tool_call.tool_call_id == tool_call_id),
            None,
        )
