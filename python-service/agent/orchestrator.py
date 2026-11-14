from __future__ import annotations

from typing import Callable, Mapping
from uuid import uuid4

from agent.events import AgentEventBus
from agent.executor import AgentExecutor
from agent.planner import AgentPlanner
from agent.state import AgentRunStatus, AgentState, PlannedStep
from workflows.router_agent import KNOWLEDGE_QA_TASK, RouteDecision, RouterAgent

#Agent 编排器 / 总调度器，是整个 Agent 流程的入口总指挥
class AgentOrchestrator:
    def __init__(
        self,
        planner: AgentPlanner | None = None,
        executor: AgentExecutor | None = None,
        router: RouterAgent | None = None,
    ) -> None:
        self._planner = planner or AgentPlanner()
        self._executor = executor or AgentExecutor()
        self._router = router or RouterAgent()

    def run(
        self,
        state: AgentState,
        #key 是字符串，value 是一个接收1 个 AgentEventBus 类型参数，没有返回值的函数。
        handlers: Mapping[str, Callable[[AgentEventBus], None]],
    ) -> AgentState:
        # 1. 创建事件总线，绑定本次任务的state
        event_bus = AgentEventBus(state)
        try:
            # 2. 先执行真实路由步骤；任何不确定或分类异常都保守降级到知识问答。
            # 创建一个计划步骤，名称为 route_intent
            route_step = PlannedStep(uuid4(), "route_intent")
            # 执行器执行路由步骤
            self._executor.execute((route_step,), {
                "route_intent": lambda _events: self._route(state),
            }, event_bus)
            # 3. 路由完成后再按最终 task_type 生成对应分支计划。
            plan = self._planner.plan(state)
            # 4. 执行器执行分支计划。
            self._executor.execute(plan, handlers, event_bus)
        except Exception:
            # 4. 只要任意异常捕获到：把全局任务状态标记 FAILED，再把异常继续往外抛
            state.status = AgentRunStatus.FAILED
            raise
        # 5. 全部正常跑完无异常：全局任务标记 COMPLETED
        state.status = AgentRunStatus.COMPLETED
        # 6. 返回更新完成的state（里面包含所有steps执行记录）
        return state

    def _route(self, state: AgentState) -> None:
        try:
            decision = self._router.route(state.original_input)
        except Exception:
            decision = RouteDecision(KNOWLEDGE_QA_TASK)
        state.task_type = decision.task_type
        state.route_key = decision.chitchat_intent.value if decision.chitchat_intent else None
