# Start the released image in the background. Arguments after the image name go to fake-llm.
docker run --rm -d --name fake-llm -p 8080:8080 ghcr.io/davidjones-griz/fake-llm:0.5.0

# Wait until it answers, then check it is up and has no scenario loaded.
curl -s --retry 30 --retry-delay 1 --retry-all-errors http://localhost:8080/health
echo

# Load the bundled scenario "hello": one step, a greeting.
curl -s -X POST http://localhost:8080/control/scenario \
  -H 'content-type: application/json' -d '{"name":"hello"}'
echo

# Call it as the Anthropic API. Call 1 gets step 1.
curl -s http://localhost:8080/anthropic/v1/messages \
  -H 'content-type: application/json' \
  -d '{"model":"example-model","max_tokens":256,"messages":[{"role":"user","content":"Hello"}]}'
echo

# What the app sent, call by call.
curl -s http://localhost:8080/control/calls
echo

# Stop the server.
docker stop fake-llm
