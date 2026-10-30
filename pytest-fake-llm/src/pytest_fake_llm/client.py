"""A client for a running fake-llm server's control API, with the standard library only."""

from __future__ import annotations

import http.client
import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, TypeVar

T = TypeVar("T")

DEFAULT_TIMEOUT = 10.0
_RAW_BODY_LIMIT = 500
# Proxy settings from the environment are not used: the server is local to the tests.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class FakeLlmClientError(Exception):
    """A failed call to the server.

    ``status_code`` is the HTTP status, or None when no complete response arrived (a transport failure
    such as a refused connection, a timeout or a body cut off). ``server_message`` is the server's own
    explanation of an error status, verbatim, when the response carried one.
    """

    def __init__(self, message: str, status_code: int | None = None, server_message: str | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.server_message = server_message


@dataclass(frozen=True)
class Health:
    """``GET /health``: the loaded scenario's name, or None, and whether the server is lenient.

    ``steps`` is the scenario's step count, None with none loaded, and ``unused_steps`` the steps no call
    has used since the last load, reset or unload, [] with none loaded. Both are None from a server older
    than 0.6.0.
    """

    scenario: str | None
    lenient: bool
    steps: int | None = None
    unused_steps: list[int] | None = None


@dataclass(frozen=True)
class ToolCallRecord:
    """One tool call a response carried."""

    id: str | None
    name: str


@dataclass(frozen=True)
class ToolResultRecord:
    """One tool result a request sent back. ``content`` is the JSON value as sent."""

    id: str | None
    name: str | None
    content: Any
    is_error: bool


@dataclass(frozen=True)
class CallRecord:
    """One entry of ``GET /control/calls``, with the fields in snake_case."""

    seq: int
    vendor: str
    model: str
    system_shape: str
    message_count: int
    outcome: str
    timestamp_ms: int
    prompt_sample: str
    step: int | None = None
    tool_calls: list[ToolCallRecord] = field(default_factory=list)
    tool_results: list[ToolResultRecord] = field(default_factory=list)
    first_byte_delay_ms: int = 0
    stream_inter_chunk_delay_ms: int = 0


class FakeLlmClient:
    """The control API of the server at ``base_url``, such as ``http://localhost:8080``.

    When ``control_token`` is set and not blank, every request sends ``Authorization: Bearer <token>``.
    Each request times out after ``timeout`` seconds. Every failed call raises FakeLlmClientError.
    """

    def __init__(self, base_url: str, control_token: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> None:
        if not timeout > 0:
            raise ValueError(f"timeout must be positive, was {timeout}")
        self.base_url = _normalized_base_url(base_url)
        self._token = control_token if control_token and control_token.strip() else None
        self._timeout = timeout

    def health(self) -> Health:
        """``GET /health``."""
        body = self._get("/health")
        return _decode(
            "GET /health",
            lambda: Health(
                scenario=body["scenario"],
                lenient=body["lenient"],
                steps=body.get("steps"),
                unused_steps=body.get("unusedSteps"),
            ),
        )

    def scenario_names(self) -> list[str]:
        """``GET /control/scenarios``: the names the server can load, sorted."""
        body = self._get("/control/scenarios")
        return _decode("GET /control/scenarios", lambda: list(body["scenarios"]))

    def load(self, name: str) -> None:
        """Loads the scenario ``name`` and starts it over: empty logs, and the next call is call 1."""
        self._send("POST", "/control/scenario", {"name": name})

    def load_inline(self, scenario: dict[str, Any]) -> None:
        """Loads ``scenario``, a scenario in the scenario file format, and starts it over."""
        self._send("POST", "/control/scenario", {"scenario": scenario})

    def reset(self) -> None:
        """Starts the loaded scenario over: empty logs, and the next call is call 1."""
        self._send("POST", "/control/reset")

    def unload(self) -> None:
        """Removes the loaded scenario and empties the logs. Needs fake-llm 0.5.0 or later."""
        self._send("POST", "/control/unload")

    def calls(self) -> list[CallRecord]:
        """``GET /control/calls``: the call log, oldest first."""
        body = self._get("/control/calls")
        return _decode("GET /control/calls", lambda: [_call_record(entry) for entry in body])

    def exchanges(self) -> list[dict[str, Any]]:
        """``GET /control/exchanges``: each call's request and response, oldest first, as JSON."""
        return self._get("/control/exchanges")

    def _get(self, path: str) -> Any:
        text = self._send("GET", path)
        try:
            return json.loads(text)
        except ValueError:
            raise FakeLlmClientError(
                f"GET {path} returned HTTP 200 with a body this client cannot read: {text[:_RAW_BODY_LIMIT]}", status_code=200
            ) from None

    def _send(self, method: str, path: str, body: dict[str, Any] | None = None) -> str:
        request = urllib.request.Request(self.base_url + path, method=method)
        if body is not None:
            request.data = json.dumps(body).encode()
            request.add_header("Content-Type", "application/json")
        if self._token is not None:
            request.add_header("Authorization", f"Bearer {self._token}")
        try:
            with _OPENER.open(request, timeout=self._timeout) as response:
                return response.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            try:
                error_body = e.read().decode(errors="replace")
            except (OSError, http.client.HTTPException) as read_error:  # the error response was cut off
                raise FakeLlmClientError(f"{method} {path} failed: {read_error!r}") from read_error
            raise _http_failure(method, path, e.code, error_body, self._token is not None) from None
        except (OSError, http.client.HTTPException) as e:  # a refused connection, a timeout, a body cut off
            raise FakeLlmClientError(f"{method} {path} failed: {e!r}") from e


def _decode(what: str, build: Callable[[], T]) -> T:
    try:
        return build()
    except (KeyError, TypeError, AttributeError) as e:
        raise FakeLlmClientError(
            f"{what} returned HTTP 200 with a body this client cannot read: missing or wrong {e}", status_code=200
        ) from None


def _call_record(entry: dict[str, Any]) -> CallRecord:
    return CallRecord(
        seq=entry["seq"],
        vendor=entry["vendor"],
        model=entry["model"],
        system_shape=entry["systemShape"],
        message_count=entry["messageCount"],
        outcome=entry["outcome"],
        timestamp_ms=entry["timestampMs"],
        prompt_sample=entry["promptSample"],
        step=entry.get("step"),
        tool_calls=[ToolCallRecord(id=c.get("id"), name=c["name"]) for c in entry.get("toolCalls", [])],
        tool_results=[
            ToolResultRecord(id=r.get("id"), name=r.get("name"), content=r.get("content"), is_error=r.get("isError", False))
            for r in entry.get("toolResults", [])
        ],
        first_byte_delay_ms=entry.get("firstByteDelayMs", 0),
        stream_inter_chunk_delay_ms=entry.get("streamInterChunkDelayMs", 0),
    )


def _server_message(body: str) -> str | None:
    """``message`` when it is a string, else ``detail`` (a server older than 0.7.0), else ``fake_llm_error``
    (the 401, 404 and 501 bodies), else the raw body's start; None when empty."""
    if not body.strip():
        return None
    try:
        parsed = json.loads(body)
    except ValueError:
        parsed = None
    if isinstance(parsed, dict):
        for key in ("message", "detail", "fake_llm_error"):
            if isinstance(parsed.get(key), str):
                return parsed[key]
    return body[:_RAW_BODY_LIMIT]


def _http_failure(method: str, path: str, status: int, body: str, token_configured: bool) -> FakeLlmClientError:
    server_message = _server_message(body)
    if status != 401:
        explanation = server_message or "(empty response body)"
    elif token_configured:
        explanation = "the server rejected the control token"
    else:
        explanation = "the server requires a control token (pass control_token, or set FAKE_LLM_CONTROL_TOKEN for the plugin)"
    return FakeLlmClientError(f"{method} {path} returned HTTP {status}: {explanation}", status, server_message)


def _normalized_base_url(raw: str) -> str:
    """``raw`` without trailing slashes; ValueError unless it is an http(s) URL with a host and no query or fragment."""
    trimmed = raw.rstrip("/")
    parts = urllib.parse.urlsplit(trimmed)
    if parts.scheme.lower() not in ("http", "https") or not parts.hostname:
        raise ValueError(f"base_url must be an http:// or https:// URL with a host, such as http://localhost:8080; was {raw!r}")
    try:
        _ = parts.port  # ValueError for a port outside 0-65535
    except ValueError:
        raise ValueError(f"base_url has a port outside 0-65535; was {raw!r}") from None
    if parts.query or parts.fragment or "?" in trimmed or "#" in trimmed:
        raise ValueError(f"base_url must not have a query or fragment; was {raw!r}")
    return trimmed
