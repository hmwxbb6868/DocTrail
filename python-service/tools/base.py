from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Any

from pydantic import BaseModel

#字符串枚举
class ToolPermission(str, Enum):
    #`ToolPermission.USER == "user"` → True
    #如果只继承 `Enum`，仅仅是枚举实例对象，它的内部存了一个值 `.value = "user"`
    USER = "user"
    ADMIN = "admin"


@dataclass(frozen=True)
#工具的元数据
class ToolMetadata:
    timeout_ms: int #工具调用超时时间,超过这个时间工具还没返回，直接判定超时失败。
    max_retries: int #最大重试次数
    retry_delay_ms: int #每次重试前等待的延时,失败后先等一会，再发起重试。
    permission: ToolPermission #工具权限

    def __post_init__(self) -> None:
        if self.timeout_ms <= 0:
            raise ValueError("tool timeout must be positive")
        if self.max_retries < 0:
            raise ValueError("tool max retries cannot be negative")
        if self.retry_delay_ms < 0:
            raise ValueError("tool retry delay cannot be negative")

#工具的抽象基类
class Tool(ABC):
    name: str
    description: str #工具描述文本
    input_model: type[BaseModel] #工具输入参数的Pydantic模型，存放的必须是一个继承自 BaseModel 的类
    output_model: type[BaseModel] #工具输出结果的Pydantic模型
    metadata: ToolMetadata #工具的元数据

    @abstractmethod
    #parameters 是继承自 BaseModel 的 Pydantic 模型实例（对象）；返回值也必须是继承自 BaseModel 的 Pydantic 模型实例
    def execute(self, parameters: BaseModel) -> BaseModel:
        """Run the tool after registry validation."""

    def is_retryable(self, error: Exception) -> bool:
        return False

    def audit_input(self, parameters: BaseModel) -> dict[str, Any]:
        return parameters.model_dump(mode="json") #转换成「适合序列化成 JSON 字符串」的类型

    def audit_output(self, output: BaseModel) -> dict[str, Any]:
        return output.model_dump(mode="json") #转换成「适合序列化成 JSON 字符串」的类型

