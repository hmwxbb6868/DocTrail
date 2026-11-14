from __future__ import annotations

from workflows.router_agent import ChitChatIntent


class ChitChatAgent:
    """Produces deterministic replies for the enumerated chitchat intents."""

    _RESPONSES = {
        ChitChatIntent.GREETING: "你好！我是 DocTrail 知识助手，很高兴见到你。",
        ChitChatIntent.THANKS: "不客气，很高兴能帮到你。",
        ChitChatIntent.FAREWELL: "再见！有问题随时来找我。",
        ChitChatIntent.IDENTITY: "我是 DocTrail 知识助手，可以基于已接入的知识库回答问题。",
        ChitChatIntent.SOCIAL_CHECK_IN: "我在，状态不错。你想聊什么？",
    }

    #拿到闲聊意图之后，输出固定回复。
    def respond(self, intent: ChitChatIntent) -> str:
        try:
            return self._RESPONSES[intent]
        except KeyError as exc:
            raise ValueError("unsupported chitchat intent") from exc
