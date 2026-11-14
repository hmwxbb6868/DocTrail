from __future__ import annotations

import json
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import faiss
import numpy as np
from fastapi import FastAPI
from fastapi.testclient import TestClient
from langchain_core.messages import HumanMessage
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnableLambda
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_ollama import ChatOllama
from langchain_openai import ChatOpenAI
from langchain_text_splitters import RecursiveCharacterTextSplitter


class _OpenAICompatibleHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        content_length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(content_length))
        if request.get("stream"):
            self._write_stream()
            return

        body = json.dumps(
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "created": 0,
                "model": "mock-model",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": "mock-response"},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2,
                },
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _write_stream(self) -> None:
        events = [
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": 0,
                "model": "mock-model",
                "choices": [
                    {"index": 0, "delta": {"role": "assistant", "content": "mock-"}, "finish_reason": None}
                ],
            },
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": 0,
                "model": "mock-model",
                "choices": [
                    {"index": 0, "delta": {"content": "stream"}, "finish_reason": None}
                ],
            },
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": 0,
                "model": "mock-model",
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            },
        ]
        payload = "".join(f"data: {json.dumps(event)}\n\n" for event in events)
        payload += "data: [DONE]\n\n"
        body = payload.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _mock_provider() -> Iterator[tuple[str, int]]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _OpenAICompatibleHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield "127.0.0.1", server.server_port
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_modern_runnable_pipeline() -> None:
    prompt = ChatPromptTemplate.from_template("Question: {question}")
    pipeline = prompt | RunnableLambda(lambda value: value.messages[0].content) | StrOutputParser()
    assert pipeline.invoke({"question": "hello"}) == "Question: hello"


def test_openai_compatible_provider_sync_and_stream() -> None:
    for host, port in _mock_provider():
        model = ChatOpenAI(
            api_key="stage-zero-mock-key",
            base_url=f"http://{host}:{port}/v1",
            model="mock-model",
            temperature=0,
            max_retries=0,
        )
        assert model.invoke([HumanMessage(content="ping")]).content == "mock-response"
        chunks = model.stream([HumanMessage(content="ping")])
        assert "".join(str(chunk.content) for chunk in chunks) == "mock-stream"


def test_maintained_provider_classes_construct_without_network() -> None:
    ollama = ChatOllama(model="mock-model", base_url="http://127.0.0.1:11434")
    huggingface = HuggingFaceEmbeddings.model_construct(model_name="BAAI/bge-small-zh-v1.5")
    assert ollama.model == "mock-model"
    assert huggingface.model_name == "BAAI/bge-small-zh-v1.5"


def test_text_splitter_and_faiss_are_compatible() -> None:
    splitter = RecursiveCharacterTextSplitter(chunk_size=8, chunk_overlap=2)
    assert len(splitter.create_documents(["DocTrail compatibility check"])) > 1

    vectors = np.array([[1.0, 0.0], [0.0, 1.0]], dtype="float32")
    index = faiss.IndexFlatIP(2)
    index.add(vectors)
    scores, positions = index.search(np.array([[1.0, 0.0]], dtype="float32"), 1)
    assert int(positions[0][0]) == 0
    assert float(scores[0][0]) == 1.0


def test_fastapi_testclient_health_fixture() -> None:
    app = FastAPI()

    @app.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "UP"}

    response = TestClient(app).get("/health/live")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}

