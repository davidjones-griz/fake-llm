"""The plugin's own tests run against the session's fake-llm server, which the plugin itself provides:
the container it starts (--fake-llm-image), or the server at --fake-llm-url."""

import os

import pytest

from pytest_fake_llm import default_image
from support import Stub


def pytest_collection_modifyitems(config, items):
    """Tests marked docker start containers of their own, so they need Docker: they run only when
    this session starts its server from the image too."""
    if not (config.getoption("fake_llm_url") or os.environ.get("FAKE_LLM_URL")):
        return
    skip = pytest.mark.skip(reason="starts containers; runs when the session starts the image (no --fake-llm-url)")
    for item in items:
        if "docker" in item.keywords:
            item.add_marker(skip)


@pytest.fixture
def image(pytestconfig):
    """The image the session started, for the tests that start their own containers."""
    return pytestconfig.getoption("fake_llm_image") or os.environ.get("FAKE_LLM_IMAGE") or default_image()


@pytest.fixture
def stub():
    """stub(status, body) starts a Stub, stopped after the test."""
    started = []

    def start(status, body, content_type="application/json"):
        started.append(Stub(status, body, content_type))
        return started[-1]

    yield start
    for s in started:
        s.close()
