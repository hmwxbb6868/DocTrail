from dataclasses import dataclass
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from application.document_service import (
    DocumentDeleteResult,
    DocumentNotFoundError,
    DocumentProcessResult,
    DocumentTooLargeError,
    DocumentUnsupportedError,
)
from core.parser import ParsedDocument
from main import create_app


TRACE_ID = "15fcdb04-b282-4c39-a51d-4fb7f948b43c"


@dataclass
class StubDocumentService:
    result: DocumentProcessResult | None = None
    error: Exception | None = None
    delete_result: DocumentDeleteResult | None = None
    last_force: bool = False

    def parse(
        self,
        doc_id: int,
        storage_key: str,
        expected_hash: str,
        force: bool = False,
    ) -> DocumentProcessResult:
        assert doc_id == 46
        if self.error is not None:
            raise self.error
        assert storage_key == "2026/09/document.pdf"
        assert len(expected_hash) == 64
        self.last_force = force
        assert self.result is not None
        return self.result

    def delete(self, doc_id: int) -> DocumentDeleteResult:
        assert doc_id == 46
        if self.error is not None:
            raise self.error
        assert self.delete_result is not None
        return self.delete_result


def _body() -> dict:
    return {
        "doc_id": 46,
        "storage_key": "2026/09/document.pdf",
        "content_hash": "0123456789abcdef" * 4,
        "force": False,
        "trace_id": TRACE_ID,
    }


def _post(service: StubDocumentService):
    client = TestClient(create_app(internal_token="test-token", document_service=service))
    return client.post(
        "/api/parse",
        json=_body(),
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )


def test_parse_route_executes_real_document_boundary_without_fake_completion() -> None:
    result = DocumentProcessResult(ParsedDocument("parsed text", "pdf", 1), uuid4(), 2, uuid4())
    response = _post(StubDocumentService(result=result))

    assert response.status_code == 200
    assert response.json() == {
        "doc_id": 46,
        "status": "COMPLETED",
        "batch_id": str(result.batch_id),
        "chunk_count": 2,
        "index_build_id": str(result.index_build_id),
        "trace_id": TRACE_ID,
        "error_message": None,
    }


@pytest.mark.parametrize(
    ("error", "expected_status"),
    [
        (DocumentNotFoundError("missing"), 404),
        (DocumentTooLargeError("too large"), 413),
        (DocumentUnsupportedError("unsupported"), 415),
    ],
)
def test_parse_route_maps_storage_and_type_errors(error: Exception, expected_status: int) -> None:
    response = _post(StubDocumentService(error=error))

    assert response.status_code == expected_status
    assert response.json()["detail"] == str(error)


def test_parse_route_rejects_trace_mismatch() -> None:
    service = StubDocumentService(
        result=DocumentProcessResult(ParsedDocument("parsed text", "pdf", 1), uuid4(), 2, uuid4())
    )
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    response = client.post(
        "/api/parse",
        json=_body(),
        headers={
            "X-Internal-Token": "test-token",
            "X-Trace-Id": "00000000-0000-0000-0000-000000000000",
        },
    )

    assert response.status_code == 400


def test_parse_route_forwards_force_semantics() -> None:
    result = DocumentProcessResult(ParsedDocument("parsed text", "pdf", 1), uuid4(), 2, uuid4())
    service = StubDocumentService(result=result)
    client = TestClient(create_app(internal_token="test-token", document_service=service))

    response = client.post(
        "/api/parse",
        json={**_body(), "force": True},
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 200
    assert service.last_force is True


def test_parse_route_maps_invalid_request_to_bad_request() -> None:
    client = TestClient(create_app(internal_token="test-token", document_service=StubDocumentService()))

    response = client.post(
        "/api/parse",
        json={**_body(), "storage_key": "../escape.pdf"},
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 400
    assert response.json() == {"detail": "Invalid parse request"}


def test_delete_route_returns_completed_delete_result() -> None:
    result = DocumentDeleteResult(46, 3, uuid4())
    client = TestClient(create_app(internal_token="test-token", document_service=StubDocumentService(delete_result=result)))

    response = client.post(
        "/api/delete",
        json={"doc_id": 46, "trace_id": TRACE_ID},
        headers={"X-Internal-Token": "test-token", "X-Trace-Id": TRACE_ID},
    )

    assert response.status_code == 200
    assert response.json() == {
        "doc_id": 46,
        "status": "DELETED",
        "deleted_chunk_count": 3,
        "trace_id": TRACE_ID,
        "error_message": None,
    }
