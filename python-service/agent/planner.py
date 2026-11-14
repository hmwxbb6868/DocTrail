from __future__ import annotations

from uuid import uuid4

from agent.state import AgentState, PlannedStep

#规划器
class AgentPlanner:
    """生成当前支持的确定性分支执行计划"""
    #写死的固定步骤元组
    _KNOWLEDGE_QA_STEPS = (
        "retrieve_active_faiss_chunks",#调用 FAISS 检索相关文本分片
        "generate_grounded_answer",#基于检索到的片段生成带引用的答案
    )
    _CHITCHAT_STEPS = ("generate_chitchat_response",)

    def plan(self, state: AgentState) -> tuple[PlannedStep, ...]:
        #PlannedStep：step_id，step_name
        if state.task_type == "knowledge_qa":
            step_names = self._KNOWLEDGE_QA_STEPS
        elif state.task_type == "chitchat":
            step_names = self._CHITCHAT_STEPS
        else:
            raise ValueError(f"unsupported task type: {state.task_type}")
        return tuple(PlannedStep(uuid4(), name) for name in step_names)
