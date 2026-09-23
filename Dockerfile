FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/super-biz-agent-1.0-SNAPSHOT.jar app.jar

RUN mkdir -p /app/config /app/db /app/uploads

EXPOSE 9900

ENTRYPOINT [
  "java",
  "-jar",
  "/app/app.jar",
  "--spring.config.additional-location=file:/app/config/"
]