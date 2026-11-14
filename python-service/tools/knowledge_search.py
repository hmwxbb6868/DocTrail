from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from application.document_service import DocumentService, DocumentServiceUnavailableError
from tools.base import Tool, ToolMetadata, ToolPermission


class KnowledgeSearchInput(BaseModel):
    #如果调用工具时，传入了 `query、top_k` 以外的字段，Pydantic 直接抛出校验异常，拒绝执行工具。
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=4000)
    top_k: int = Field(default=5, ge=1, le=20)


class KnowledgeSearchMatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    score: float = Field(ge=0, le=1)
    chunk_id: int = Field(gt=0)
    doc_id: int = Field(gt=0)
    content: str
    doc_name: str | None
    chunk_index: int = Field(ge=0)


class KnowledgeSearchOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matches: list[KnowledgeSearchMatch]

#
class KnowledgeSearchTool(Tool):
    name = "retrieve_active_faiss_chunks"
    description = "Search the formal active FAISS index and resolve matching active MySQL chunks."
    input_model = KnowledgeSearchInput
    output_model = KnowledgeSearchOutput

    def __init__(
        self,
        documents: DocumentService,
        *,
        timeout_ms: int = 30_000,
        max_retries: int = 1,
        retry_delay_ms: int = 100,
    ) -> None:
        self._documents = documents
        self.metadata = ToolMetadata(
            timeout_ms=timeout_ms,
            max_retries=max_retries,
            retry_delay_ms=retry_delay_ms,
            permission=ToolPermission.USER,
        )

    def execute(self, parameters: BaseModel) -> BaseModel:
        #`BaseModel` 里面内置定义了 `model_validate`
        request = KnowledgeSearchInput.model_validate(parameters)
        # 调用文档服务的检索接口，返回匹配的文档块
        matches = self._documents.retrieve(request.query, top_k=request.top_k)
        return KnowledgeSearchOutput(matches=[
            KnowledgeSearchMatch(
                score=max(0.0, min(1.0, match.score)),
                chunk_id=chunk.chunk_id,
                doc_id=chunk.doc_id,
                content=chunk.content,
                doc_name=chunk.doc_name,
                chunk_index=chunk.chunk_index,
            )
            for match, chunk in matches
        ])

    def is_retryable(self, error: Exception) -> bool:
        return isinstance(error, DocumentServiceUnavailableError)

    def audit_input(self, parameters: BaseModel) -> dict[str, Any]:
        request = KnowledgeSearchInput.model_validate(parameters)
        return {"length": len(request.query), "limit": request.top_k}

    def audit_output(self, output: BaseModel) -> dict[str, Any]:
        result = KnowledgeSearchOutput.model_validate(output)
        return {
            "matches": len(result.matches),
            "documents": list(dict.fromkeys(match.doc_id for match in result.matches)),
            "chunks": [match.chunk_id for match in result.matches],
        }
