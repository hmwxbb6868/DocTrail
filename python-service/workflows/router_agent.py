from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass
from enum import Enum
from typing import Protocol

#区分用户输入是闲聊，还是知识库问答

logger = logging.getLogger(__name__)

KNOWLEDGE_QA_TASK = "knowledge_qa"
CHITCHAT_TASK = "chitchat"


class ChitChatIntent(str, Enum):
    GREETING = "greeting"
    THANKS = "thanks"
    FAREWELL = "farewell"
    IDENTITY = "identity"
    SOCIAL_CHECK_IN = "social_check_in"


@dataclass(frozen=True)
#只读的数据盒子，不可随意改值
class RouteDecision:
    task_type: str #字符串，二选一：`"chitchat"` 或者 `"knowledge_qa"`
    chitchat_intent: ChitChatIntent | None = None #闲聊意图，如果task_type是`"chitchat"`，则此字段必填


class IntentClassifier(Protocol):
    def classify(self, input_text: str) -> ChitChatIntent | None: ...


class EnumeratedIntentClassifier:
    """Matches only complete, explicitly enumerated short chitchat phrases."""
    #建立【闲聊类型 ↔ 对应的触发短句】对照表。
    PHRASES: dict[ChitChatIntent, frozenset[str]] = {
        ChitChatIntent.GREETING: frozenset({
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "早上好", "下午好", "晚上好",
        }),
        ChitChatIntent.THANKS: frozenset({
            "谢谢", "感谢", "多谢", "谢谢你", "感谢你", "thanks", "thank you",
        }),
        ChitChatIntent.FAREWELL: frozenset({
            "再见", "拜拜", "下次见", "晚安", "bye", "goodbye",
        }),
        ChitChatIntent.IDENTITY: frozenset({
            "你是谁", "你叫什么", "你叫什么名字", "你是什么", "你能做什么",
        }),
        ChitChatIntent.SOCIAL_CHECK_IN: frozenset({
            "在吗", "你好吗", "最近怎么样", "最近好吗", "今天过得怎么样",
        }),
    }
    _TRAILING_PUNCTUATION = re.compile(r"[\s。！？!?，,；;：:～~…\.]+$")

    def __init__(self) -> None:
        self._phrase_to_intent = {
            phrase: intent
            for intent, phrases in self.PHRASES.items()
            for phrase in phrases
        }

    #实现用户输入文本的标准化预处理
    @classmethod
    def normalize(cls, input_text: str) -> str:
        #将输入文本进行规范化处理
        normalized = unicodedata.normalize("NFKC", input_text).casefold().strip()
        #将输入文本中的所有连续空白字符替换为单个空格
        normalized = re.sub(r"\s+", " ", normalized)
        #删除输入文本末尾的尾随标点符号
        return cls._TRAILING_PUNCTUATION.sub("", normalized).strip()

    #实现用户输入文本的闲聊意图分类
    def classify(self, input_text: str) -> ChitChatIntent | None:
        normalized = self.normalize(input_text)
        if not normalized:
            return None
        return self._phrase_to_intent.get(normalized)

#任务路由组件，采用保守路由策略：只有明确识别为闲聊的请求才进入闲聊分支，其余全部交由知识库问答处理。
class RouterAgent:
    """Routes conservatively: anything not certainly chitchat is knowledge QA."""

    def __init__(self, classifier: IntentClassifier | None = None) -> None:
        self._classifier = classifier or EnumeratedIntentClassifier()

    def route(self, input_text: str) -> RouteDecision:
        try:
            intent = self._classifier.classify(input_text)
        except Exception as exc:
            logger.warning("intent classification failed; falling back to knowledge_qa: %s", type(exc).__name__)
            return RouteDecision(KNOWLEDGE_QA_TASK)
        if not isinstance(intent, ChitChatIntent):
            return RouteDecision(KNOWLEDGE_QA_TASK)
        return RouteDecision(CHITCHAT_TASK, intent)
