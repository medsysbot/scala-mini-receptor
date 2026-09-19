FROM sbtscala/scala-sbt:eclipse-temurin-21.0.4_7_1.10.2_3.5.2
WORKDIR /app

COPY build.sbt ./
COPY src ./src

RUN sbt compile

ENV PORT=8080
EXPOSE 8080

CMD ["sbt", "run"]
