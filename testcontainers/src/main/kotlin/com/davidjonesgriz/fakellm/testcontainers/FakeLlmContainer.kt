package com.davidjonesgriz.fakellm.testcontainers

import com.davidjonesgriz.fakellm.FakeLlmClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * fake-llm's Docker image as a Testcontainers container. It listens on [PORT] inside the container,
 * and counts as started once `GET /health` answers 200.
 *
 * The no-argument constructor runs [DEFAULT_IMAGE], the image released with this library's version.
 * Another image, such as a mirror or a local build, must be declared a substitute for it:
 * `FakeLlmContainer(DockerImageName.parse("fake-llm:ci").asCompatibleSubstituteFor("ghcr.io/davidjones-griz/fake-llm"))`.
 */
public class FakeLlmContainer(
    image: DockerImageName,
) : GenericContainer<FakeLlmContainer>(image) {
    public constructor() : this(DEFAULT_IMAGE)

    public constructor(image: String) : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(IMAGE_NAME)
        withExposedPorts(PORT)
        waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(HTTP_OK))
    }

    /** `http://<host>:<mapped port>`, the server's address from the test. Valid once the container has started. */
    public val baseUrl: String
        get() = "http://$host:${getMappedPort(PORT)}"

    /** The base URL for the Anthropic SDKs, which append `/v1/messages`. */
    public val anthropicBaseUrl: String
        get() = "$baseUrl/anthropic"

    /** The base URL for the OpenAI SDKs, which append `/chat/completions` or `/responses`. */
    public val openAiBaseUrl: String
        get() = "$baseUrl/openai/v1"

    /** The base URL for the `google-genai` SDKs, which append `v1beta/models/<model>:generateContent`. */
    public val geminiBaseUrl: String
        get() = "$baseUrl/gemini/"

    /**
     * Copies [directory]'s files into the container before it starts, and sets `FAKE_LLM_SCENARIO_DIR`
     * to the copy, so its scenarios load by name. The copies get mode 0755, so the server, which runs as
     * a non-root user, can read them whatever their host permissions, as in a JUnit `@TempDir`.
     */
    public fun withScenarioDirectory(directory: Path): FakeLlmContainer =
        withCopyFileToContainer(MountableFile.forHostPath(directory, READABLE_BY_ALL), SCENARIO_DIRECTORY)
            .withEnv("FAKE_LLM_SCENARIO_DIR", SCENARIO_DIRECTORY)

    /** Loads the scenario [name] at startup (`FAKE_LLM_SCENARIO`). */
    public fun withScenario(name: String): FakeLlmContainer = withEnv("FAKE_LLM_SCENARIO", name)

    /** Turns lenient mode on (`FAKE_LLM_LENIENT=true`): a call with no scenario loaded gets a canned reply. */
    public fun withLenient(): FakeLlmContainer = withEnv("FAKE_LLM_LENIENT", "true")

    /** Requires `Authorization: Bearer <token>` on the control routes (`FAKE_LLM_CONTROL_TOKEN`). [client] sends it. */
    public fun withControlToken(token: String): FakeLlmContainer = withEnv("FAKE_LLM_CONTROL_TOKEN", token)

    /** A control API client for this container, with its control token if one is set. Close it after use. */
    public fun client(): FakeLlmClient = FakeLlmClient(baseUrl, envMap["FAKE_LLM_CONTROL_TOKEN"])

    public companion object {
        /** The port the server listens on inside the container. */
        public const val PORT: Int = 8080

        /** The repository fake-llm's image is released to. Any tag of it can be used without declaring a substitute. */
        public const val IMAGE_REPOSITORY: String = "ghcr.io/davidjones-griz/fake-llm"

        /** Where [withScenarioDirectory] copies the scenarios to, inside the container. */
        public const val SCENARIO_DIRECTORY: String = "/scenarios"

        /** `ghcr.io/davidjones-griz/fake-llm`, tagged with this library's version. */
        @JvmField
        public val DEFAULT_IMAGE: DockerImageName = DockerImageName.parse(IMAGE_REPOSITORY).withTag(libraryVersion())

        private val IMAGE_NAME = DockerImageName.parse(IMAGE_REPOSITORY)
        private const val HTTP_OK = 200

        // 0755: directories must be searchable, and files readable, by the server's user.
        private const val READABLE_BY_ALL = 0b111_101_101

        private fun libraryVersion(): String {
            val resource = checkNotNull(FakeLlmContainer::class.java.getResource("fake-llm.version")) { "fake-llm.version is missing" }
            return resource.readText().trim()
        }
    }
}
