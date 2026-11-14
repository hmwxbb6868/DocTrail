from __future__ import annotations

import json
import os
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


READY_URL = "http://python-service:8000/health/ready"
POLL_INTERVAL_SECONDS = 5


def sanitized_summary(payload: Any) -> str:
    if not isinstance(payload, dict):
        return "unparseable response"
    overall = str(payload.get("status", "UNKNOWN"))[:32]
    checks = payload.get("checks")
    safe_checks: list[str] = []
    if isinstance(checks, list):
        for check in checks:
            if isinstance(check, dict):
                name = str(check.get("name", "unknown"))[:64]
                status = str(check.get("status", "UNKNOWN"))[:32]
                safe_checks.append(f"{name}={status}")
    return f"status={overall}; checks={','.join(safe_checks) or 'none'}"


def request_readiness() -> tuple[bool, str]:
    try:
        with urlopen(READY_URL, timeout=10) as response:
            payload = json.load(response)
            return 200 <= response.status < 300, sanitized_summary(payload)
    except HTTPError as exc:
        try:
            payload = json.load(exc)
        except (json.JSONDecodeError, UnicodeDecodeError):
            payload = None
        return False, sanitized_summary(payload)
    except (OSError, URLError, json.JSONDecodeError, UnicodeDecodeError):
        return False, "request failed"


def main() -> None:
    try:
        timeout_seconds = int(os.getenv("PYTHON_READY_TIMEOUT_SECONDS", "600"))
    except ValueError as exc:
        raise SystemExit("PYTHON_READY_TIMEOUT_SECONDS must be a positive integer") from exc
    if timeout_seconds <= 0:
        raise SystemExit("PYTHON_READY_TIMEOUT_SECONDS must be a positive integer")

    deadline = time.monotonic() + timeout_seconds
    last_summary = "no response"
    while True:
        ready, last_summary = request_readiness()
        if ready:
            print(f"python readiness passed: {last_summary}")
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise SystemExit(f"python readiness timed out: {last_summary}")
        time.sleep(min(POLL_INTERVAL_SECONDS, remaining))


if __name__ == "__main__":
    main()
