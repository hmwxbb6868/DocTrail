from datetime import datetime

from fastapi.testclient import TestClient

from main import app as production_app, create_app
from application.document_service import (
    DocumentServiceUnavailableError,
    ReadinessCheckResult,
    ReadinessResult,
)
#这整个文件不是业务代码，而是一个针对健康检查 API 的契约测试套件：
#模拟请求这两个接口，确认它们返回的结果符合约定。
#它确保 /health/live 和 /health/ready 的状态码、响应字段、字段值、
# 时间格式以及 OpenAPI 文档都符合预先约定的标准。


#/health/live 顶层应该包含的字段
LIVE_FIELDS = {"status", "service", "timestamp", "mock"}
# /health/ready 顶层应该包含的字段
READY_FIELDS = {"status", "service", "timestamp", "mock", "checks"}
# checks 中每个检查项应该包含的字段。
CHECK_FIELDS = {"name", "status", "detail"}

#assert 条件, 可选报错提示文字
#- ✅ 如果 `条件 == True`：什么都不做，代码继续往下跑
#- ❌ 如果 `条件 == False`：直接抛出 `AssertionError`，程序终止


#用来检查时间字符串是否包含 UTC 时区
def assert_utc_timestamp(value: str) -> None:
    #接收接口返回的时间字符串。把字符串解析为 datetime 对象。
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    #检查它是否带时区，以及 UTC 偏移是否为 0
    assert parsed.utcoffset() is not None
    assert parsed.utcoffset().total_seconds() == 0

#测试存活检查接口
#只要一个 assert 不成立，测试就失败
def test_liveness_matches_contract() -> None:
    #创建 FastAPI 应用。
    # 为这个应用创建测试客户端。
    # 模拟发送 GET /health/live 请求。
    # 响应对象保存在 response 中。
    response = TestClient(create_app()).get("/health/live")

    assert response.status_code == 200
    #把接口返回的**JSON 响应体**，转成 Python 字典
    payload = response.json()
    #`payload` 是字典，`set(payload)` 提取字典**所有 key（键名）**，转为集合
    #检查 `payload` 字典是否只包含 `LIVE_FIELDS` 集合中的字段
    assert set(payload) == LIVE_FIELDS
    assert payload["status"] == "UP"
    assert payload["service"] == "doctrail-python"
    assert payload["mock"] is True
    assert_utc_timestamp(payload["timestamp"])


def test_real_application_liveness_is_not_mock() -> None:
    response = TestClient(production_app).get("/health/live")

    assert response.status_code == 200
    payload = response.json()
    assert set(payload) == LIVE_FIELDS
    assert payload["status"] == "UP"
    assert payload["service"] == "doctrail-python"
    assert payload["mock"] is False
    assert_utc_timestamp(payload["timestamp"])

#测试默认就绪状态
def test_readiness_stub_is_ready_by_default() -> None:
    #TestClient(create_app())可以理解为创建了一个“虚拟浏览器”
    response = TestClient(create_app()).get("/health/ready")

    assert response.status_code == 200
    payload = response.json()
    assert set(payload) == READY_FIELDS
    assert payload["status"] == "READY"
    assert payload["service"] == "doctrail-python"
    assert payload["mock"] is True
    assert payload["checks"] == [
        {
            "name": "contract_stub",
            "status": "UP",
            "detail": "Stage 1 contract stub is ready.",
        }
    ]
    assert set(payload["checks"][0]) == CHECK_FIELDS
    assert_utc_timestamp(payload["timestamp"])

#测试未就绪状态
#它是在验证“未就绪路径”。这里没有真实数据库，也没有模型服务。False 只是人为模拟：
#假设依赖服务坏了，代码能不能正确返回 503？
def test_readiness_returns_503_when_stub_is_not_ready() -> None:
    response = TestClient(create_app(contract_stub_ready=False)).get("/health/ready")

    assert response.status_code == 503
    payload = response.json()
    assert set(payload) == READY_FIELDS
    assert payload["status"] == "NOT_READY"
    assert payload["service"] == "doctrail-python"
    assert payload["mock"] is True
    assert payload["checks"] == [
        {
            "name": "contract_stub",
            "status": "DOWN",
            "detail": "Stage 1 contract stub is not ready.",
        }
    ]
    assert set(payload["checks"][0]) == CHECK_FIELDS
    assert_utc_timestamp(payload["timestamp"])


def test_real_application_readiness_is_not_mock_when_dependencies_are_unavailable(monkeypatch) -> None:
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    def unavailable_service():
        raise DocumentServiceUnavailableError("test configuration is incomplete")

    monkeypatch.setattr("main.create_document_service_from_env", unavailable_service)
    response = TestClient(create_app(contract_stub_ready=False, real_service=True)).get("/health/ready")

    assert response.status_code == 503
    payload = response.json()
    assert set(payload) == READY_FIELDS
    assert payload["status"] == "NOT_READY"
    assert payload["mock"] is False
    assert {check["name"] for check in payload["checks"]} >= {"llm_provider", "document_service"}


class ReadyDocumentService:
    def __init__(self, ready: bool):
        self._ready = ready

    def readiness(self):
        return ReadinessResult(
            self._ready,
            (
                ReadinessCheckResult("mysql_active_chunks", "UP", "0 active chunks"),
                ReadinessCheckResult("faiss_index", "UP" if self._ready else "DOWN", None if self._ready else "rebuild failed"),
            ),
        )


def test_readiness_uses_real_document_service_checks(monkeypatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-only-readiness-placeholder")
    response = TestClient(
        create_app(internal_token="test-token", document_service=ReadyDocumentService(True))
    ).get("/health/ready")
    assert response.status_code == 200
    assert response.json()["mock"] is False
    checks = {check["name"]: check for check in response.json()["checks"]}
    assert checks["internal_token"]["status"] == "UP"
    assert checks["llm_provider"]["status"] == "UP"
    assert checks["mysql_active_chunks"]["status"] == "UP"
    assert checks["faiss_index"]["status"] == "UP"


def test_readiness_returns_503_when_deepseek_is_not_configured(monkeypatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "deepseek")
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    response = TestClient(create_app(document_service=ReadyDocumentService(True))).get("/health/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "NOT_READY"
    checks = {check["name"]: check for check in response.json()["checks"]}
    assert checks["llm_provider"]["status"] == "DOWN"
    assert "not configured" in checks["llm_provider"]["detail"]


def test_readiness_returns_503_when_internal_token_is_not_configured(monkeypatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-only-readiness-placeholder")
    monkeypatch.delenv("INTERNAL_TOKEN", raising=False)
    response = TestClient(create_app(document_service=ReadyDocumentService(True))).get("/health/ready")

    assert response.status_code == 503
    checks = {check["name"]: check for check in response.json()["checks"]}
    assert checks["internal_token"]["status"] == "DOWN"


def test_readiness_returns_503_when_index_rebuild_cannot_recover() -> None:
    response = TestClient(create_app(document_service=ReadyDocumentService(False))).get("/health/ready")
    assert response.status_code == 503
    assert response.json()["status"] == "NOT_READY"

#测试生成的 OpenAPI 文档
#这个测试不访问健康检查接口，而是检查 FastAPI 自动生成的接口说明书。
def test_generated_openapi_exposes_frozen_health_statuses() -> None:
    spec = create_app().openapi()

    assert {"/health/live", "/health/ready"} <= set(spec["paths"])
    #从 OpenAPI 文档中取出 /health/live 的 GET 操作定义。
    live_operation = spec["paths"]["/health/live"]["get"]
    #从 OpenAPI 文档中取出 /health/ready 的 GET 操作定义。
    ready_operation = spec["paths"]["/health/ready"]["get"]
    #检查存活接口的 OpenAPI 操作标识是否为 getLiveness。
    assert live_operation["operationId"] == "getLiveness"
    #检查就绪接口的操作标识是否为 getReadiness。
    assert ready_operation["operationId"] == "getReadiness"
    #检查存活接口的响应状态码是否只包含 200。
    assert set(live_operation["responses"]) == {"200"}
    #检查就绪接口的响应状态码是否包含 200 和 503。
    assert set(ready_operation["responses"]) == {
        "200",
        "503",
    }
