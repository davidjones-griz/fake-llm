# Recipes

Each recipe tests one situation an app that calls a model has to handle. Each uses a scenario bundled with fake-llm, so it loads by name on any server, with no files of your own, and fake-llm's own test suite runs each one.

- [Retries after an error](retries.md): a server error, then a reply. The app should retry once and succeed.
- [Truncated output (max_tokens)](max-tokens.md): a reply cut off by the token limit. The app should not treat it as complete.
- [Malformed JSON output](malformed-json.md): prose around a broken JSON fragment, then valid JSON. The app should notice and ask again.
- [Mid-stream failures](mid-stream-failure.md): a stream that fails after its first chunks. The app should not keep the partial text as the answer.
- [Diagnosing the no-scenario and no-more-steps errors](diagnosing-errors.md): the two errors fake-llm returns when a call has no step, and its other 400s.

The examples show a Kotlin test with the [JUnit Jupiter extension](../library.md#junit-jupiter-extension) and a Python test with the [pytest plugin](../pytest.md). In each, the app's own code (the class or function the test calls) stands in for yours. For a test suite of your own, copy a scenario into your project and name it after its test; see [Writing scenarios for tests](../scenarios.md#writing-scenarios-for-tests).
