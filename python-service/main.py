import logging
import os
import secrets
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from httpx import TimeoutException as HttpTimeoutException
from openai import APITimeoutError

from application.document_service import (
    ContentHashMismatchError,
    DocumentInvalidError,
    DocumentNotFoundError,
    DocumentService,
    DocumentServiceUnavailableError,
    DocumentTooLargeError,
    DocumentUnsupportedError,
    IndexBuildError,
    DocumentDeleteResult,
    VectorStoreStatsResult,
    create_document_service_from_env,
)
from application.ask_service import (
    AskHistoryMessage,
    AskModelUnavailableError,
    AskService,
    answer_model_configuration_status,
    create_answer_model_from_env,
)
from application.run_service import AgentRunService
from agent.state import AgentState
from storage import StorageKeyError, normalize_relative_key


SERVICE_NAME = "doctrail-python"
MAX_HISTORY_MESSAGES = 20
MAX_HISTORY_UTF8_BYTES = 8192
HISTORY_MESSAGE_OVERHEAD_BYTES = 64
logger = logging.getLogger(__name__)

#模拟请求这两个接口，确认它们返回的结果符合约定。

class ContractModel(BaseModel):
    #表示禁止返回模型中未定义的额外字段
    model_config = ConfigDict(extra="forbid")

#存活检查响应模型
class LivenessResponse(ContractModel):
    status: Literal["UP"]
    service: Literal["doctrail-python"]
    timestamp: datetime
    mock: bool

#就绪检查模型
class ReadinessCheck(ContractModel):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]*$")
    status: Literal["UP", "DOWN"]
    #可以是字符串，也可以是 None。默认值是 None
    detail: str | None = None

#就绪检查响应模型
class ReadinessResponse(ContractModel):
    status: Literal["READY", "NOT_READY"]
    service: Literal["doctrail-python"]
    timestamp: datetime
    mock: bool
    checks: list[ReadinessCheck] = Field(min_length=1)

#对话历史里单条消息的结构
class AskHistoryItem(ContractModel):
    message_id: int = Field(gt=0)
    role: Literal["user", "assistant", "system"]
    content: str

#AI 问答接口
class AskRequest(ContractModel):
    question: str = Field(min_length=1, max_length=4000)
    conversation_id: int = Field(gt=0)
    user_id: int | None = Field(default=None, gt=0)
    history: list[AskHistoryItem] = Field(max_length=MAX_HISTORY_MESSAGES)
    history_version: int | None = Field(default=None, gt=0)
    username: str | None = Field(default=None, max_length=64)
    is_admin: bool
    request_id: UUID
    run_id: UUID
    trace_id: UUID
    include_trace: bool

    #在 `AskRequest` 模型完成字段解析之后执行
    @model_validator(mode="after")
    #统计对话历史全部消息的总字节，超出阈值则拒绝请求，控制上下文预算。
    def _validate_history_budget(self) -> "AskRequest":
        history_bytes = sum(
            len(item.content.encode("utf-8")) + HISTORY_MESSAGE_OVERHEAD_BYTES
            for item in self.history
        )
        if history_bytes > MAX_HISTORY_UTF8_BYTES:
            raise ValueError("history exceeds the 8192-byte UTF-8 budget")
        return self

#检索出来的相关文档片段的来源信息
class AskSource(ContractModel):
    doc_id: int = Field(gt=0)
    doc_name: str
    page: int | None = Field(default=None, ge=1) #文档页码
    chunk_index: int | None = Field(default=None, ge=0) #文档切片块的下标
    score: float | None = Field(default=None, ge=0, le=1) #向量检索相似度得分.范围 0~1，越接近 1 代表和用户提问越相关

#大模型调用之后，返回的 token 消耗和计费信息
class AskUsage(ContractModel):
    prompt_tokens: int = Field(ge=0) #提示词 token 数量
    completion_tokens: int = Field(ge=0) #大模型输出回答消耗的 token 数量
    total_tokens: int = Field(ge=0) #总 token 数量
    estimated_cost: str | None = Field(pattern=r"^\d+(\.\d{1,6})?$") #估计的费用；无法计算时为 null

#   记录 AI 问答整条链路的执行追踪信息，用于日志、排查问题
class TraceStep(ContractModel):
    step_id: UUID
    step_name: str
    status: Literal["RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
    duration_ms: int | None = Field(default=None, ge=0)
    error_code: int | None = None
    error_message: str | None = None


class TraceToolCall(ContractModel):
    tool_call_id: UUID
    tool_name: str
    status: Literal["RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
    input_params: dict | None = None # 工具入参字典，记录传给工具的参数
    output: dict | None = None # 工具返回结果字典，调用成功后填充
    duration_ms: int | None = Field(default=None, ge=0)
    error_code: int | None = None
    error_message: str | None = None


class ExecutionTrace(ContractModel):
    status: Literal["COMPLETED", "FAILED", "CANCELLED"]
    steps: list[TraceStep] = Field(default_factory=list)
    tool_calls: list[TraceToolCall] = Field(default_factory=list)


class AskResponse(ContractModel):
    answer: str #AI 最终给用户的回答文本
    sources: list[AskSource] #引用的知识库来源列表
    task_type: Literal["knowledge_qa", "retrieval", "chitchat", "reasoning"] #任务类型
    run_id: UUID #运行 ID
    trace_id: UUID #跟踪 ID
    usage: AskUsage | None #token 消耗和计费信息
    execution_trace: ExecutionTrace #执行追踪信息
    mock: bool

#run 的概要信息（基础链路 ID、任务类型、运行状态）
class AgentRunDiagnosticResponse(ContractModel):
    request_id: UUID
    run_id: UUID
    trace_id: UUID
    conversation_id: int = Field(gt=0)
    user_id: int | None = Field(default=None, gt=0)
    task_type: Literal["knowledge_qa", "retrieval", "chitchat", "reasoning"]
    status: Literal["RUNNING", "COMPLETED", "FAILED", "CANCELLED"]


#run 的详细执行步骤信息（基础链路 ID、执行步骤列表）
class AgentRunStepsResponse(ContractModel):
    run_id: UUID
    steps: list[TraceStep]

#run 的详细工具调用信息（基础链路 ID、工具调用列表）
class AgentRunToolCallsResponse(ContractModel):
    run_id: UUID
    tool_calls: list[TraceToolCall]


class ParseRequest(ContractModel):
    doc_id: int = Field(gt=0)   #文档 ID
    storage_key: str = Field(   #文件在对象存储里的路径 / 键名
        min_length=1,
        max_length=512,
        # pydantic-core 的 Rust regex 不支持 look-ahead，"不以 / 开头"由首字符
        # 必须为字母数字来保证；"不含 .."由下方 validator 在 Python 层校验
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._/-]*$",
    )
#对模型里名叫 `storage_key` 的字段做自定义校验
    @field_validator("storage_key")
    @classmethod
    def _no_path_traversal(cls, v: str) -> str:
        try:
            return normalize_relative_key(v)
        except StorageKeyError as exc:
            raise ValueError(str(exc)) from exc
     #文件内容哈希值，用来校验文件内容有没有改动
    content_hash: str = Field(pattern=r"^[a-f0-9]{64}$")
    force: bool  #是否强制重新解析文档
    trace_id: UUID #全链路追踪 UUID


class ParseResponse(ContractModel):
    doc_id: int = Field(gt=0)
    status: Literal["COMPLETED", "FAILED"] #解析结果状态
    batch_id: UUID | None #批量解析任务 ID
    chunk_count: int = Field(ge=0) #文档切片块数量
    index_build_id: UUID | None #索引构建 ID：解析完 chunk 后，要把向量存入向量数据库建立索引
    trace_id: UUID
    error_message: str | None = Field(default=None, max_length=500) #错误信息


class DeleteRequest(ContractModel):
    doc_id: int = Field(gt=0)
    trace_id: UUID

# 告诉后端向量库里面该文档的切片清理结果
class DeleteResponse(ContractModel):
    doc_id: int = Field(gt=0)
    status: Literal["DELETED", "FAILED"]
    deleted_chunk_count: int = Field(ge=0)
    trace_id: UUID
    error_message: str | None = Field(default=None, max_length=500)


class VectorStoreStatsResponse(ContractModel):
    backend: Literal["faiss"]
    index_build_id: UUID | None
    embedding_model: str | None
    dimension: int | None = Field(gt=0)
    normalized: bool | None
    chunk_count: int | None = Field(ge=0)
    active_chunk_digest: str | None = Field(pattern=r"^[a-f0-9]{64}$")
    created_at: datetime | None
    ready: bool
    trace_id: UUID

#获取当前 UTC 时间
def utc_now() -> datetime:
    return datetime.now(timezone.utc)

# 创建 FastAPI 应用
#表示“契约模拟检查”是否就绪。默认值是 True
def create_app(
    *,#后面所有参数必须用关键字传参
    contract_stub_ready: bool = True,
    real_service: bool = False,
    internal_token: str | None = None,
    document_service: DocumentService | None = None,
    ask_service: AskService | None = None,
    run_service: AgentRunService | None = None,
) -> FastAPI:
    app = FastAPI(title="DocTrail Python Service", version="0.1.0")
    app.state.contract_stub_ready = contract_stub_ready
    app.state.real_service = real_service
    app.state.internal_token = os.getenv("INTERNAL_TOKEN", "") if internal_token is None else internal_token
    app.state.document_service = document_service
    app.state.ask_service = ask_service
    app.state.run_service = (
        run_service
        or getattr(ask_service, "run_service", None)
        or AgentRunService()
    )

    @app.exception_handler(RequestValidationError)
    async def parse_validation_error_handler(request, exc: RequestValidationError):
        if request.url.path in {"/api/parse", "/api/delete"}:
            return JSONResponse(status_code=400, content={"detail": "Invalid parse request"})
        if request.url.path in {"/api/vector-store/stats", "/api/vector-store/rebuild"}:
            return JSONResponse(status_code=400, content={"detail": "Invalid vector store request"})
        return JSONResponse(status_code=422, content={"detail": "Request validation failed"})

    def get_document_service() -> DocumentService:
        if app.state.document_service is None:
            #如果全局还没有 DocumentService，就自动从环境变量创建一个并存到 `app.state`
            app.state.document_service = create_document_service_from_env()
        return app.state.document_service

    def get_ask_service() -> AskService:
        if app.state.ask_service is None:
            app.state.ask_service = AskService(
                get_document_service(),
                create_answer_model_from_env(),
                run_service=app.state.run_service,
            )
        return app.state.ask_service

    def get_run_state(run_id: UUID) -> AgentState:
        state = app.state.run_service.find(run_id)
        if state is None:
            raise HTTPException(status_code=404, detail="Agent run not found in this process")
        return state

    def require_internal_token(
        x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
    ) -> None:
        expected = app.state.internal_token
        if not expected:
            raise HTTPException(status_code=500, detail="Internal token is not configured")
        if x_internal_token is None or not secrets.compare_digest(x_internal_token, expected):
            raise HTTPException(status_code=401, detail="Invalid internal token")

    #注册存活检查接口
    #这个接口的作用是检查：服务进程是否还活着。通过返回 200 OK 来表示服务进程还活着。
    @app.get(
        "/health/live",
        response_model=LivenessResponse,
        #operation_id="getLiveness",
        # 为 OpenAPI 文档中的接口指定稳定名称，方便生成客户端 SDK。
        operation_id="getLiveness",
    )
    def get_liveness() -> LivenessResponse:
        return LivenessResponse(
            status="UP",
            service=SERVICE_NAME,
            timestamp=utc_now(),
            mock=not app.state.real_service,
        )
    #注册就绪检查接口
    #它检查：服务是否已经准备好接收实际请求。
    @app.get(
        "/health/ready",
        response_model=ReadinessResponse,
        operation_id="getReadiness",
        #说明接口在返回 503 Service Unavailable 时，响应体仍然符合 ReadinessResponse 格式
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ReadinessResponse}},
    )
    #status: Literal["READY", "NOT_READY"]；service: Literal["doctrail-python"]
    #timestamp，mock，checks: list[ReadinessCheck] = Field(min_length=1)
    def get_readiness(response: Response) -> ReadinessResponse:
        # ========== 契约桩 Mock 分支（测试环境使用，生产环境不会进） ==========
        # 条件：真实文档服务未初始化，并且契约桩标记为就绪
        if app.state.document_service is None and app.state.contract_stub_ready:
            return ReadinessResponse(
                status="READY", service=SERVICE_NAME, timestamp=utc_now(), mock=True,
                ## 检查项列表：契约桩正常
                checks=[ReadinessCheck(name="contract_stub", status="UP", detail="Stage 1 contract stub is ready.")],
            )
        ## 条件：真实文档服务未初始化，契约桩也未就绪
        if (
            app.state.document_service is None
            and not app.state.contract_stub_ready
            and not app.state.real_service
        ):
            #设置HTTP 503，K8s识别为未就绪
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            return ReadinessResponse(
                status="NOT_READY", service=SERVICE_NAME, timestamp=utc_now(), mock=True,
                checks=[ReadinessCheck(name="contract_stub", status="DOWN", detail="Stage 1 contract stub is not ready.")],
            )
        # ============================ 真实业务分支（生产环境走这里） =====================
        model_ready, model_detail = answer_model_configuration_status()
        token_ready = bool(app.state.internal_token and app.state.internal_token.strip())
        checks = [
            ReadinessCheck(
                name="internal_token",
                status="UP" if token_ready else "DOWN",
                detail="Internal token is configured" if token_ready else "Internal token is not configured",
            ),
            ReadinessCheck(
                name="llm_provider",
                status="UP" if model_ready else "DOWN",
                detail=model_detail,
            ),
        ]
        try:
            service = get_document_service()
            result = service.readiness() # 调用业务层readiness自检方法，获取文档服务实例失败，捕获专属异常
        except DocumentServiceUnavailableError as exc:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            checks.append(ReadinessCheck(name="document_service", status="DOWN", detail=str(exc)[:500]))
            return ReadinessResponse(
                status="NOT_READY", service=SERVICE_NAME, timestamp=utc_now(), mock=False,# 真实服务，不是mock
                checks=checks,
            )
            # 如果业务自检返回未就绪，设置HTTP 503
        checks.extend(
            ReadinessCheck(name=check.name, status=check.status, detail=check.detail)
            for check in result.checks
        )
        if not result.ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        if not model_ready or not token_ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            # 组装最终返回报文，把业务层readiness返回的检查明细，转为对外接口的DTO
        return ReadinessResponse(
            status="READY" if result.ready and model_ready and token_ready else "NOT_READY",
            service=SERVICE_NAME,
            timestamp=utc_now(),
            mock=False,
            # 遍历业务层返回的checks元组，转换成接口输出的ReadinessCheck对象列表
            checks=checks,
        )

    #`aiAskClient.ask()` 方法远程调用的目标服务端点
    @app.post("/api/ask", response_model=AskResponse, operation_id="ask")
    def ask(
        request: AskRequest,
        _: None = Depends(require_internal_token),
        x_trace_id: UUID = Header(..., alias="X-Trace-Id"),
    ) -> AskResponse:
        if x_trace_id != request.trace_id:
            raise HTTPException(status_code=400, detail="X-Trace-Id does not match trace_id")
        logger.info(
            "stage5 ask accepted request_id=%s run_id=%s trace_id=%s",
            request.request_id,
            request.run_id,
            request.trace_id,
        )
        try:
            result = get_ask_service().ask(
                request.question,
                history=[
                    AskHistoryMessage(item.message_id, item.role, item.content)
                    for item in request.history
                ],
                request_id=request.request_id,
                run_id=request.run_id,
                trace_id=request.trace_id,
                conversation_id=request.conversation_id,
                user_id=request.user_id,
                is_admin=request.is_admin,
            )
        except (DocumentServiceUnavailableError, AskModelUnavailableError) as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        except (HttpTimeoutException, APITimeoutError) as exc:
            raise HTTPException(status_code=504, detail="AI model request timed out") from exc
        except Exception as exc:
            logger.warning("ask provider failed request_id=%s error_type=%s", request.request_id, type(exc).__name__)
            raise HTTPException(status_code=502, detail="AI model request failed") from exc
        return AskResponse(
            answer=result.answer,
            task_type=result.task_type,
            run_id=request.run_id,
            trace_id=request.trace_id,
            sources=[
                AskSource(
                    doc_id=source.doc_id,
                    doc_name=source.doc_name,
                    chunk_index=source.chunk_index,
                    score=source.score,
                )
                for source in result.sources
            ],
            usage=AskUsage(**result.usage) if result.usage else None,
            execution_trace=ExecutionTrace(
                status=result.status.value,
                steps=[TraceStep(
                    step_id=step.step_id,
                    step_name=step.step_name,
                    status=step.status.value,
                    duration_ms=step.duration_ms,
                    error_code=step.error_code,
                    error_message=step.error_message,
                ) for step in result.steps],
                tool_calls=[TraceToolCall(
                    tool_call_id=tool_call.tool_call_id,
                    tool_name=tool_call.tool_name,
                    status=tool_call.status.value,
                    input_params=tool_call.input_params,
                    output=tool_call.output,
                    duration_ms=tool_call.duration_ms,
                    error_code=tool_call.error_code,
                    error_message=tool_call.error_message,
                ) for tool_call in result.tool_calls],
            ),
            mock=False,
        )

    @app.get(
        "/api/agent/run/{run_id}",
        response_model=AgentRunDiagnosticResponse,
        operation_id="getAgentRunDiagnostic",
    )
    def get_agent_run_diagnostic(
        run_id: UUID,
        #校验 token，判断是不是内部服务 / 内部调用方，拦截外部非法请求
        _: None = Depends(require_internal_token),
    ) -> AgentRunDiagnosticResponse:
        state = get_run_state(run_id)
        return AgentRunDiagnosticResponse(
            request_id=state.request_id,
            run_id=state.run_id,
            trace_id=state.trace_id,
            conversation_id=state.conversation_id,
            user_id=state.user_id,
            task_type=state.task_type,
            status=state.status.value,
        )

    @app.get(
        "/api/agent/run/{run_id}/steps",
        response_model=AgentRunStepsResponse,
        operation_id="getAgentRunStepsDiagnostic",
    )
    def get_agent_run_steps_diagnostic(
        run_id: UUID,
        _: None = Depends(require_internal_token),
    ) -> AgentRunStepsResponse:
        state = get_run_state(run_id)
        return AgentRunStepsResponse(
            run_id=state.run_id,
            steps=[TraceStep(
                step_id=step.step_id,
                step_name=step.step_name,
                status=step.status.value,
                duration_ms=step.duration_ms,
                error_code=step.error_code,
                error_message=step.error_message,
            ) for step in state.steps],
        )

    @app.get(
        "/api/agent/run/{run_id}/tool-calls",
        response_model=AgentRunToolCallsResponse,
        operation_id="getAgentRunToolCallsDiagnostic",
    )
    def get_agent_run_tool_calls_diagnostic(
        run_id: UUID,
        _: None = Depends(require_internal_token),
    ) -> AgentRunToolCallsResponse:
        state = get_run_state(run_id)
        return AgentRunToolCallsResponse(
            run_id=state.run_id,
            tool_calls=[TraceToolCall(
                tool_call_id=tool_call.tool_call_id,
                tool_name=tool_call.tool_name,
                status=tool_call.status.value,
                input_params=tool_call.input_params,
                output=tool_call.output,
                duration_ms=tool_call.duration_ms,
                error_code=tool_call.error_code,
                error_message=tool_call.error_message,
            ) for tool_call in state.tool_calls],
        )

    @app.post(
        "/api/ask/stream",
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        operation_id="askStreamUnavailable",
        responses={
            status.HTTP_501_NOT_IMPLEMENTED: {
                "description": "Real SSE is cancelled in the current route"
            }
        },
    )
    def ask_stream_unavailable(
        _: None = Depends(require_internal_token),
    ) -> JSONResponse:
        """Compatibility path only; real SSE is cancelled in this route."""
        return JSONResponse(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            content={"detail": "streaming is not implemented in the current route"},
        )

    @app.get(
        "/api/vector-store/stats",
        response_model=VectorStoreStatsResponse,#把函数 return 的 Python 对象，序列化成 JSON
        operation_id="getVectorStoreStats",#给这个接口一个全局唯一稳定名称
        #当返回 HTTP 503 状态码时，响应体的 JSON 结构依然遵循 `VectorStoreStatsResponse` 这个模型。
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": VectorStoreStatsResponse}},
    )
    def get_vector_store_stats(
        response: Response,
        _: None = Depends(require_internal_token),
        x_trace_id: UUID = Header(..., alias="X-Trace-Id"),
    ) -> VectorStoreStatsResponse:
    #=========================文档服务本身不可用，无法读取向量统计信息。返回的 DTO 里 index_build_id、摘要等全部是 null。=======
        try:
            result: VectorStoreStatsResult = get_document_service().stats()
        except DocumentServiceUnavailableError:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            return VectorStoreStatsResponse(
                backend="faiss",
                index_build_id=None,
                embedding_model=None,
                dimension=None,
                normalized=None,
                chunk_count=None,
                active_chunk_digest=None,
                created_at=None,
                ready=False,
                trace_id=x_trace_id,
            )
            #===================服务活着，可以查询状态，但是向量索引和 MySQL 分片对账失败，不能对外提供检索。============
        if not result.ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return VectorStoreStatsResponse(
            backend="faiss",
            index_build_id=result.index_build_id,
            embedding_model=result.embedding_model,
            dimension=result.dimension,
            normalized=result.normalized,
            chunk_count=result.chunk_count,
            active_chunk_digest=result.active_chunk_digest,
            created_at=result.created_at,
            ready=result.ready,
            trace_id=x_trace_id,
        )

    @app.post(
        "/api/vector-store/rebuild",
        response_model=VectorStoreStatsResponse,
        operation_id="rebuildVectorStore",
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": VectorStoreStatsResponse}},
    )
    def rebuild_vector_store(
        response: Response,
        _: None = Depends(require_internal_token),
        x_trace_id: UUID = Header(..., alias="X-Trace-Id"),
    ) -> VectorStoreStatsResponse:
        try:
            result: VectorStoreStatsResult = get_document_service().rebuild_index()
        except (DocumentServiceUnavailableError, IndexBuildError):
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            return VectorStoreStatsResponse(
                backend="faiss",
                index_build_id=None,
                embedding_model=None,
                dimension=None,
                normalized=None,
                chunk_count=None,
                active_chunk_digest=None,
                created_at=None,
                ready=False,
                trace_id=x_trace_id,
            )
        return VectorStoreStatsResponse(
            backend="faiss",
            index_build_id=result.index_build_id,
            embedding_model=result.embedding_model,
            dimension=result.dimension,
            normalized=result.normalized,
            chunk_count=result.chunk_count,
            active_chunk_digest=result.active_chunk_digest,
            created_at=result.created_at,
            ready=True,
            trace_id=x_trace_id,
        )

    @app.post("/api/parse", response_model=ParseResponse, operation_id="parseDocument")
    def parse_document(
        request: ParseRequest,
        _: None = Depends(require_internal_token),#**内部鉴权，校验失败直接拒绝请求；校验成功，什么都不用返回
        x_trace_id: UUID = Header(..., alias="X-Trace-Id"),
    ) -> ParseResponse:
        if x_trace_id != request.trace_id:
            raise HTTPException(status_code=400, detail="X-Trace-Id does not match trace_id")
        try:
            parsed = get_document_service().parse(
                request.doc_id,
                request.storage_key,
                request.content_hash,
                request.force,
            )
        except DocumentNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except DocumentTooLargeError as exc:
            raise HTTPException(status_code=413, detail=str(exc)) from exc
        except DocumentUnsupportedError as exc:
            raise HTTPException(status_code=415, detail=str(exc)) from exc
        except DocumentServiceUnavailableError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        except IndexBuildError as exc:
            return _failed_parse_response(
                request,
                str(exc),
                batch_id=exc.batch_id,
                chunk_count=exc.chunk_count,
            )
        except (ContentHashMismatchError, DocumentInvalidError) as exc:
            return _failed_parse_response(request, str(exc))

        logger.info(
            "document parsed doc_id=%s type=%s units=%s chars=%s chunks=%s batch_id=%s trace_id=%s",
            request.doc_id,
            parsed.parsed.document_type,
            parsed.parsed.unit_count,
            len(parsed.parsed.text),
            parsed.chunk_count,
            parsed.batch_id,
            request.trace_id,
        )
        return ParseResponse(
            doc_id=request.doc_id,
            status="COMPLETED",
            batch_id=parsed.batch_id,
            chunk_count=parsed.chunk_count,
            index_build_id=parsed.index_build_id,
            trace_id=request.trace_id,
            error_message=None,
        )

    @app.post("/api/delete", response_model=DeleteResponse, operation_id="deleteDocument")
    def delete_document(
        #DeleteRequest：doc_id,trace_id
        request: DeleteRequest,
        _: None = Depends(require_internal_token),
        x_trace_id: UUID = Header(..., alias="X-Trace-Id"),
    ) -> DeleteResponse:
        if x_trace_id != request.trace_id:
            raise HTTPException(status_code=400, detail="X-Trace-Id does not match trace_id")
        try:
            deleted: DocumentDeleteResult = get_document_service().delete(request.doc_id)
            #文档服务不可用异常
        except DocumentServiceUnavailableError as exc:
            return DeleteResponse(
                doc_id=request.doc_id,
                status="FAILED",
                deleted_chunk_count=0,
                trace_id=request.trace_id,
                error_message=str(exc)[:500],
            )
            #索引构建异常
        except IndexBuildError as exc:
            return DeleteResponse(
                doc_id=request.doc_id,
                status="FAILED",
                deleted_chunk_count=exc.chunk_count,
                trace_id=request.trace_id,
                error_message=str(exc)[:500],
            )
        return DeleteResponse(
            doc_id=deleted.doc_id,
            status="DELETED",
            deleted_chunk_count=deleted.deleted_chunk_count,
            trace_id=request.trace_id,
            error_message=None,
        )

    return app


def _failed_parse_response(
    request: ParseRequest,
    message: str,
    *,
    batch_id=None,
    chunk_count: int = 0,
) -> ParseResponse:
    safe_message = message if len(message) <= 500 else message[:500]
    return ParseResponse(
        doc_id=request.doc_id,
        status="FAILED",
        batch_id=batch_id,
        chunk_count=chunk_count,
        index_build_id=None,
        trace_id=request.trace_id,
        error_message=safe_message,
    )


# Keep process import/startup independent from external MySQL, object storage,
# embedding-model, and FAISS availability.  The real service is created lazily
# on the first business request; /health/ready can therefore report NOT_READY
# instead of the process failing before the readiness endpoint exists.
app = create_app(contract_stub_ready=False, real_service=True)
