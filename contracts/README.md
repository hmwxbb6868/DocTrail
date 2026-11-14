# Contracts Directory

This directory is the source of truth for browser-to-Java, Java-to-Python, and SSE contracts. Implementations must follow these files; generated Spring or FastAPI documentation must not overwrite them.

## Current Slice

Stage 4 (including slice 13) is complete and has been submitted to GitHub.
The stage-4 document runtime (parse, chunk persistence, FAISS publication,
delete, retry, readiness, legacy isolation, and storage-key boundary checks)
is complete. Stage 5 has passed overall acceptance: slices 1–6 (V4 migration
boundary, Trace DTO contract, RUNNING gate, synchronous RAG lifecycle,
vector-store statistics, and manual rebuild), the Playwright E2E flow, and
GitHub contract CI have all been verified. Stage 6 slices 1–5 have passed user
acceptance; slice 5 adds the conservative Router/ChitChat branch while retaining
the same HTTP paths and response schemas. The `v1-reproduce` release gate has
been approved, and the annotated tag has been created by the user on the
stage-exit commit `d52da1f` and pushed to `origin`. The formal Java application chain validates
conversation ownership, commits RUNNING, calls Python, then persists the
success snapshot/log or a replayable failure.
The slice-3-only diagnostic controller at
`POST /api/chat/message-preparations` is removed after integration into the
formal lifecycle; it is not part of `public-openapi.yaml`. History is supplied
by Java from MySQL, limited to the newest contiguous suffix of at most 20
complete messages and 8192 UTF-8 bytes (content plus 64 fixed bytes per item),
and injected into the model prompt as prior conversation context. A scheduled
Java worker recovers requests older than 900 seconds in a short transaction.
The Playwright E2E entry point and no-secret contract CI are checked in for
stage-5 closeout; the user has confirmed the E2E flow and GitHub Actions
contract run succeeded. Stage 6 is complete. The active work is stage 10
slice 1 local containerized delivery; its Docker and full-chain runtime checks
remain future user verification points.
The current route remains:

- Public contract v1.2.0 additively defines Java `GET /health/live` at the root server `/`, not below the global `/api` server. It proves only that the Java process and embedded HTTP server are responsive and must not call MySQL, Redis, Python, or a model. The implementation is still pending in stage 10 slice 1.
- The Java operations response is not wrapped in the business `Result<T>` envelope, and Nginx must not expose this path publicly; Compose calls it inside the Java container.
- Internal-contract `GET /health/live` proves that the Python HTTP process is responsive. The real service must report `mock=false`; only an explicit contract stub may report `mock=true`.
- A real `GET /health/ready` checks DeepSeek provider configuration plus the active-chunk/FAISS manifest state; it does not make a potentially billable model call. Actual provider reachability is verified through `/api/ask`.
- Python readiness can repair an inconsistent FAISS index in the current implementation, so Compose must not use it as a periodic healthcheck; it is a low-frequency readiness/diagnostic action.
- A live process can still return HTTP 503 and `NOT_READY` from the readiness endpoint.
- Stage-6 `/api/ask` responses contain the actual `knowledge_qa` or `chitchat` task type and must set `mock=false`.
- Health endpoints do not require `X-Internal-Token`, but deployment must restrict them to localhost or the container network.
- Stage 1 placeholder responses set `mock` to `true`; real implementations must set it to `false` once their checks are active.

`POST /api/ask` is the synchronous routed Agent endpoint protected by
`X-Internal-Token`; `X-Trace-Id` must equal the body `trace_id`, and the
response must use `mock=false`. Exact enumerated chitchat phrases return empty
sources/usage/tool calls; unmatched, mixed, uncertain, or classifier-error
input falls back to the formal-index `knowledge_qa` branch. Historical stage-1 placeholder
payloads remain only in tests or migration examples and must not be described
as a successful stage-5 answer.
GET `/chat/messages` returns a `sources` array on every `Message`; successful
assistant messages restore it from the persisted user-row `answer_snapshot`
identified by `reply_to_message_id`, while user/system/failed messages return
an empty array.
`POST /api/parse` accepts `doc_id`, a relative `storage_key`, a lowercase
SHA-256 `content_hash`, `force`, and `trace_id`. `POST /api/delete` accepts
`doc_id` and `trace_id`. Both endpoints require the internal token and trace
header; neither endpoint accepts a host absolute path.
`public-openapi.yaml` freezes the paths used by the accepted DocTrail frontend.
User knowledge access is read-only; upload and delete are administrator-only.
`sse-events.yaml` is frozen for compatibility only. Real SSE is cancelled in the current route; public and internal stream paths return HTTP 501 with `Result.code=5010`.

## Contract Files

- `public-openapi.yaml`: browser-to-Java HTTP/JSON contract (v1) plus the root Java container operations liveness path.
- `ai-openapi.yaml`: Java-to-Python health, synchronous routed ask, Agent diagnostics, document lifecycle, and FAISS rebuild/statistics contracts.
- `sse-events.yaml`: frozen streaming names, fields, order, and termination rules for compatibility; runtime is not implemented in the current route.
- `examples/`: request and response examples referenced by the contracts.

## Frozen Rules

- Public JSON uses `camelCase`; Java-to-Python JSON uses `snake_case`.
- Business IDs use `int64`; request, trace, run, step, and tool-call IDs use UUID strings.
- Cross-service timestamps use UTC RFC 3339 values.
- Additive optional fields are compatible changes. Removing fields, changing types or requiredness, and changing error semantics require a new major contract version.
- Change contracts before implementations. Use a `contract:` commit prefix for contract changes.
- Example payloads must parse and match the documented field names; secrets and production data are forbidden.

## Stage 1 Contract Validation

Run from the DocTrail repository root in PowerShell:

```powershell
Push-Location '.\python-service'
& '.\.venv\Scripts\python.exe' -m pytest '.\tests\test_contract_files.py' -q
Pop-Location

Get-ChildItem '.\contracts\examples\*.json' | ForEach-Object {
    Get-Content -Raw $_.FullName | ConvertFrom-Json | Out-Null
    Write-Host "$($_.Name): OK"
}
```

The contract test parses all three YAML files, checks required public and
internal paths and operation metadata, parses the ask/parse/delete examples,
and validates them against the Python Pydantic models. A full OpenAPI 3.1
semantic linter remains a future CI improvement and is not claimed by this
test.
