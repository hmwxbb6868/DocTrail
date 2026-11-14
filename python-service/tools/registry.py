from __future__ import annotations

from queue import Empty, Queue
from threading import Thread
from time import perf_counter, sleep
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, ValidationError

from agent.events import AgentEventBus, AgentToolEvent, AgentToolEventType
from tools.base import Tool, ToolPermission


#工具注册中心 + 统一工具调用调度器

class ToolRegistryError(RuntimeError):
    pass


class ToolNotFoundError(ToolRegistryError):
    pass


class ToolPermissionDeniedError(ToolRegistryError):
    pass


class ToolSchemaError(ToolRegistryError):
    pass


class ToolTimeoutError(ToolRegistryError):
    pass


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        if not tool.name or tool.name in self._tools:
            raise ValueError(f"duplicate or empty tool name: {tool.name}")
        self._tools[tool.name] = tool

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    #工具调用的「外层调度壳子」
    def invoke(
        self,
        name: str,# 工具名称，用来在注册表查找工具
        parameters: dict[str, Any],
        *,
        caller_permission: ToolPermission,# 调用者的权限（USER / ADMIN）
        event_bus: AgentEventBus,# 事件总线
    ) -> BaseModel:
        tool = self.get(name)
        if tool is None:
            raise ToolNotFoundError(f"tool is not registered: {name}")
        if tool.metadata.permission == ToolPermission.ADMIN and caller_permission != ToolPermission.ADMIN:
            raise ToolPermissionDeniedError("caller cannot invoke this tool")
        try:
            validated_input = tool.input_model.model_validate(parameters)
        except ValidationError as error:
            raise ToolSchemaError("tool input violated schema") from error

        call_id = uuid4()
        event_bus.publish(AgentToolEvent(
            event_type=AgentToolEventType.TOOL_STARTED,
            tool_call_id=call_id,
            tool_name=tool.name,
            input_params=tool.audit_input(validated_input),
        ))
        started = perf_counter()
        attempts = 0
        try:
            while True:
                attempts += 1
                try:
                    #带超时保护的环境里运行工具的业务 execute 方法返回的结果，赋值给变量`raw_output`
                    raw_output = self._execute_with_timeout(tool, validated_input)
                    try:
                        output = tool.output_model.model_validate(raw_output)
                    except ValidationError as error:
                        raise ToolSchemaError("tool output violated schema") from error
                    event_bus.publish(AgentToolEvent(
                        event_type=AgentToolEventType.TOOL_COMPLETED,
                        tool_call_id=call_id,
                        tool_name=tool.name,
                        output=tool.audit_output(output),
                        duration_ms=self._duration_ms(started),
                    ))
                    return output
                except Exception as error:
                    #已经尝试次数 > 最大允许重试次数或者当前这个异常不支持重试
                    if attempts > tool.metadata.max_retries or not tool.is_retryable(error):
                        raise
                    #如果配置了重试等待时间，主线程就休眠等待指定毫秒，再进入下一轮重试
                    if tool.metadata.retry_delay_ms:
                        sleep(tool.metadata.retry_delay_ms / 1000)
        except Exception as error:
            event_bus.publish(AgentToolEvent(
                event_type=AgentToolEventType.TOOL_FAILED,
                tool_call_id=call_id,
                tool_name=tool.name,
                duration_ms=self._duration_ms(started),
                error_code=self._error_code(error),
                error_message=self._safe_error_message(error),
            ))
            raise

    @staticmethod
    def _execute_with_timeout(tool: Tool, parameters: BaseModel) -> BaseModel:
        result_queue: Queue[tuple[bool, object]] = Queue(maxsize=1)

        def execute() -> None:
            # 子线程内部：往队列 put 数据
            try:
                result_queue.put((True, tool.execute(parameters)))
            except Exception as error:
                result_queue.put((False, error))
        # 开启子线程，跑上面execute函数
        Thread(target=execute, name=f"tool-{tool.name}", daemon=True).start()
        try:
            # ========== 主线程：从队列 get 拿数据 ==========
            succeeded, value = result_queue.get(timeout=tool.metadata.timeout_ms / 1000)

        except Empty as error:
            raise ToolTimeoutError("tool execution timed out") from error
        if succeeded:
            return value
        raise value

    @staticmethod
    def _duration_ms(started: float) -> int:
        return max(0, int((perf_counter() - started) * 1000))

    @staticmethod
    def _error_code(error: Exception) -> int:
        if isinstance(error, ToolTimeoutError):
            return 3002
        if isinstance(error, ToolSchemaError):
            return 3003
        return 3001

    @staticmethod
    def _safe_error_message(error: Exception) -> str:
        if isinstance(error, ToolTimeoutError):
            return "Tool execution timed out"
        if isinstance(error, ToolSchemaError):
            return "Tool schema validation failed"
        return "Tool execution failed"

