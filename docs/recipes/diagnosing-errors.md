# Diagnosing the no-scenario, no-more-steps and no-match errors

fake-llm answers a call that has no step with HTTP 400 and a JSON body: `fake_llm_error` is a code that says which case it is, and `message` says why. There are three cases. The first two usually mean the test and the app disagree about how many calls the app makes; the third, `no-match`, comes from a scenario for calls made in parallel. These examples use the bundled scenario `hello`, which has one step:

```json
--8<-- "src/main/resources/com/davidjonesgriz/fakellm/scenarios/hello.json"
```

## no-more-steps

A call after the scenario's last step gets:

```json
{
  "fake_llm_error": "no-more-steps",
  "message": "no more steps: call 2 came after the last step of scenario 'hello', which has 1 step. The last step does not repeat.",
  "scenario": "hello",
  "steps": 1,
  "seq": 2,
  "vendor": "anthropic",
  "model": "example-model",
  "system_shape": "absent",
  "message_count": 1,
  "prompt_preview": "Hello\n"
}
```

`seq` is the call's number, and `steps` how many the scenario has, so this was call 2 of a one-step scenario. `prompt_preview` is the start of what the app sent, which usually identifies the call. Common causes:

- The app made a call the test didn't expect: a retry, a follow-up after a tool call, or a second request from a library the app uses.
- The scenario wasn't started over between tests, so this test's calls continued the last test's numbering. Load or reset the scenario at the start of each test; the JUnit extension and the pytest plugin do this for you.
- An earlier call in the same test failed in a way the app retried, so every later call moved up one step.

`GET /control/calls` lists every call in order, with its `outcome` and the step that served it; the call that got the 400 has outcome `no-more-steps` and `step` null. `GET /health`'s `unusedSteps` shows the opposite mistake, steps no call used.

## no-scenario

With no scenario loaded, a call gets the same body without `scenario` and `steps`, and `"fake_llm_error": "no-scenario"`. Its message says how to load a scenario. Common causes:

- The test never loaded a scenario, or loaded it on a different server than the one the app calls (check the app's base URL).
- The scenario was unloaded, for example by the pytest plugin before each test, and this test has no `fake_llm_scenario` marker.

A server started with `--lenient` or `FAKE_LLM_LENIENT=true` answers this case with a canned reply instead. Lenient mode is for trying fake-llm by hand; a test is clearer with an explicit scenario. A call after the last step is a 400 in either mode.

## no-match

A call that matched none of the steps it could have taken gets `"fake_llm_error": "no-match"`, with the call fields and a message listing those steps and their rules:

```json
{"fake_llm_error":"no-match","message":"no match: call 3 matched none of the unused steps it could have taken: step 1.2 (lastUser contains \"doc-b\")","seq":3,"vendor":"anthropic","model":"example-model","system_shape":"absent","message_count":1,"prompt_preview":"Summarize doc-z\n"}
```

It comes only from a scenario with a [match or a parallel step](../scenarios.md#parallel-calls). Common causes:

- The call came earlier than the scenario expects: the step it matches is after a parallel step that isn't used up yet, or after a plain step with a `match` that is still next.
- A rule doesn't say what the test author meant: compare the call's `prompt_preview`, or its entry in `GET /control/exchanges`, with the rules in the message.
- Two calls match the same step, so the second finds it used.

The call takes a number and an entry in the call log, with outcome `no-match`, and uses nothing.

## Other 400s

A 400 with another code means the request never became a call, so it took no step and is not in the call log: `invalid-json` and `invalid-request` for a body the route can't use, `unsupported-step` for a next step whose `rawArguments` the request's format can't carry, and `unknown-scenario` or `invalid-scenario` for a scenario load that failed. [fake-llm's error responses](../endpoints.md#fake-llms-error-responses) lists every code.

## Testing how the app handles them

The JUnit extension fails a test that makes a call with no step, because an app that catches the 400 could otherwise pass. A test that checks the app's handling of this error on purpose opts out with `@FakeLlmAllowUnservedCalls`:

```kotlin
@FakeLlmTest
class GreeterTest {
    @Test
    @FakeLlmScenario("hello")
    @FakeLlmAllowUnservedCalls
    fun `a second greeting reports the error`(server: FakeLlmServer) {
        val greeter = Greeter(baseUrl = "${server.baseUrl}/anthropic")
        greeter.greet()

        assertFailsWith<GreetingFailedException> { greeter.greet() }
        assertEquals(listOf("text", "no-more-steps"), server.engine.calls().map { it.outcome })
    }
}
```

With the `anthropic` Python SDK, the 400 is a `BadRequestError` whose `body` is the JSON above:

```python
import anthropic
import pytest


@pytest.mark.fake_llm_scenario("hello")
def test_a_second_call_gets_no_more_steps(fake_llm):
    client = anthropic.Anthropic(api_key="not-used", base_url=fake_llm.anthropic_base_url)
    request = dict(model="example-model", max_tokens=256, messages=[{"role": "user", "content": "Hello"}])
    client.messages.create(**request)
    with pytest.raises(anthropic.BadRequestError) as raised:
        client.messages.create(**request)
    assert raised.value.body["fake_llm_error"] == "no-more-steps"
    assert raised.value.body["seq"] == 2
```
