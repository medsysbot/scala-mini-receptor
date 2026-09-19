FROM sbtscala/scala-sbt:eclipse-temurin-21.0.4_7_1.10.2_3.5.2 AS build
WORKDIR /app
COPY build.sbt ./
COPY src ./src
RUN sbt clean compile stage

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/universal/stage ./
ENV PORT=8080
EXPOSE 8080
CMD ["bin/scala-mini-receptor"]
