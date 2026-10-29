import importlib.metadata
import pathlib

from pytest_fake_llm import default_image

GRADLE_PROPERTIES = pathlib.Path(__file__).parents[2] / "gradle.properties"


def test_the_version_and_the_default_image_are_fake_llms():
    [version] = [line.removeprefix("version=") for line in GRADLE_PROPERTIES.read_text().splitlines() if line.startswith("version=")]

    assert importlib.metadata.version("pytest-fake-llm") == version
    assert default_image() == f"ghcr.io/davidjones-griz/fake-llm:{version}"
