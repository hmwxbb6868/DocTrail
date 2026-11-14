import pytest

from workflows.chitchat_agent import ChitChatAgent
from workflows.router_agent import (
    CHITCHAT_TASK,
    KNOWLEDGE_QA_TASK,
    ChitChatIntent,
    RouterAgent,
)


@pytest.mark.parametrize("text", [
    "你好", "您好！", " HELLO ", "谢谢你。", "再见!!", "你是谁？", "你能做什么？", "在吗",
])
def test_enumerated_complete_phrases_route_to_chitchat(text: str) -> None:
    decision = RouterAgent().route(text)

    assert decision.task_type == CHITCHAT_TASK
    assert isinstance(decision.chitchat_intent, ChitChatIntent)


@pytest.mark.parametrize("text", [
    "什么是 RAG？",
    "你好，请解释 RAG",
    "谢谢你解释一下 MySQL 事务",
    "今天天气怎么样",
    "讲个笑话背后的语言学原理是什么？",
    "帮我分析这个问题",
    "",
])
def test_unmatched_mixed_or_uncertain_text_routes_to_knowledge_qa(text: str) -> None:
    decision = RouterAgent().route(text)

    assert decision.task_type == KNOWLEDGE_QA_TASK
    assert decision.chitchat_intent is None


def test_classifier_exception_routes_to_knowledge_qa() -> None:
    class FailingClassifier:
        def classify(self, input_text: str):
            raise RuntimeError("classifier unavailable")

    decision = RouterAgent(FailingClassifier()).route("你好")

    assert decision.task_type == KNOWLEDGE_QA_TASK
    assert decision.chitchat_intent is None


def test_unknown_classifier_result_routes_to_knowledge_qa() -> None:
    class UnknownClassifier:
        def classify(self, input_text: str):
            return "unsupported_intent"

    decision = RouterAgent(UnknownClassifier()).route("你好")

    assert decision.task_type == KNOWLEDGE_QA_TASK
    assert decision.chitchat_intent is None


@pytest.mark.parametrize("intent", list(ChitChatIntent))
def test_chitchat_agent_has_a_non_empty_deterministic_reply(intent: ChitChatIntent) -> None:
    agent = ChitChatAgent()

    assert agent.respond(intent) == agent.respond(intent)
    assert agent.respond(intent).strip()
