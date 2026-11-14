import os
from dataclasses import dataclass

from langchain_text_splitters import RecursiveCharacterTextSplitter


class TextSplitConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class TextChunk:
    index: int # 分片序号（第几个片段）
    content: str # 分片文本内容


class DocumentTextSplitter:
    """`DocumentTextSplitter` → 调用 langchain 的切分器 → 得到字符串分片
     → 组装成 `TextChunk(index, content)` 列表
     → 交给 `MysqlChunkStore.replace_active_chunks` 存入数据库。"""
#   `chunk_size`：单个分片最大字符长度
#   `chunk_overlap`：分片之间重叠的字符长度
    def __init__(self, chunk_size: int = 800, chunk_overlap: int = 120) -> None:
        if chunk_size <= 0:
            raise TextSplitConfigurationError("chunk size must be positive")
        if chunk_size > 10_000:
            raise TextSplitConfigurationError("chunk size must not exceed 10000")
        if chunk_overlap < 0 or chunk_overlap >= chunk_size:
            raise TextSplitConfigurationError("chunk overlap must be smaller than chunk size")
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        # 创建LangChain的递归切分器实例
        self._splitter = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            separators=["\n\n", "\n", " ", ""],
            length_function=len,
        )
#     `text`：**整篇文档的原始长文本字符串**
    def split(self, text: str) -> list[TextChunk]:
        if not isinstance(text, str) or not text.strip():
            raise ValueError("document text must not be blank")
            #自动按我们配置好的 `chunk_size`、`chunk_overlap`、`separators` 切分，**返回字符串列表**
        chunks = self._splitter.split_text(text)
        #`TextChunk(index=index, content=content.strip())`，
        #`enumerate(列表)` 的作用：遍历列表的时候，同时拿到 序号 (index) 和 列表里的元素 (content)。
        #，新建一个`TextChunk`实例
        return [TextChunk(index=index, content=content.strip()) for index, content in enumerate(chunks) if content.strip()]

#   classmethod：类方法，`cls` 代表**类本身**，也就是 `DocumentTextSplitter`
    @classmethod
    def from_env(cls) -> "DocumentTextSplitter":
        return cls(
            chunk_size=_positive_int_env("KNOWLEDGE_CHUNK_SIZE", 800),
            chunk_overlap=_non_negative_int_env("KNOWLEDGE_CHUNK_OVERLAP", 120),
        )


def _positive_int_env(name: str, default: int) -> int:
    value = _int_env(name, default)
    if value <= 0:
        raise TextSplitConfigurationError(f"{name} must be positive")
    return value


def _non_negative_int_env(name: str, default: int) -> int:
    value = _int_env(name, default)
    if value < 0:
        raise TextSplitConfigurationError(f"{name} must not be negative")
    return value


def _int_env(name: str, default: int) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        return int(raw_value)
    except ValueError as exc:
        raise TextSplitConfigurationError(f"{name} must be an integer") from exc
