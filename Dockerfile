# Base images are pinned by digest; Dependabot proposes digest updates (see .github/dependabot.yml).
FROM eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d AS build
WORKDIR /src
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin fakellm
WORKDIR /app
COPY --from=build /src/build/libs/fake-llm-all.jar /app/fake-llm-all.jar
# The fakellm user, by UID, so the runtime user is unambiguous without reading /etc/passwd.
USER 10001
EXPOSE 8080
# Exec form: arguments after the image name are appended, so
# `docker run fake-llm --scenario hello` reaches the process.
ENTRYPOINT ["java", "-jar", "/app/fake-llm-all.jar"]
