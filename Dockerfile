FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin fakellm
WORKDIR /app
COPY --from=build /src/build/libs/fake-llm-all.jar /app/fake-llm-all.jar
USER fakellm
EXPOSE 8080
# Exec form: arguments after the image name are appended, so
# `docker run fake-llm --scenario hello` reaches the process.
ENTRYPOINT ["java", "-jar", "/app/fake-llm-all.jar"]
