"""pytest fixtures for fake-llm, and a client for its control API."""

from pytest_fake_llm.client import (
    CallRecord,
    FakeLlmClient,
    FakeLlmClientError,
    Health,
    ToolCallRecord,
    ToolResultRecord,
)
from pytest_fake_llm.plugin import FakeLlmServer, default_image

__all__ = [
    "CallRecord",
    "FakeLlmClient",
    "FakeLlmClientError",
    "FakeLlmServer",
    "Health",
    "ToolCallRecord",
    "ToolResultRecord",
    "default_image",
]
