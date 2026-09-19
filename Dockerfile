FROM eclipse-temurin:17-jdk AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends scala \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY src/main/scala/Main.scala .
RUN scalac Main.scala \
    && jar cfe scala-mini-receptor.jar Main *.class

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/scala-mini-receptor.jar /app/scala-mini-receptor.jar

ENV PORT=8080
EXPOSE 8080

CMD ["java", "--add-modules", "jdk.httpserver", "-jar", "/app/scala-mini-receptor.jar"]
