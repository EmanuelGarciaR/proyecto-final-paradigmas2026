FROM sbtscala/scala-sbt:eclipse-temurin-21.0.4_7_1.10.2_3.3.4 AS builder

WORKDIR /app

COPY project ./project
COPY build.sbt ./
RUN sbt update

COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre-alpine AS runner

RUN apk add --no-cache curl tzdata

WORKDIR /app

COPY --from=builder /app/target/scala-3.3.4/backend-scala.jar ./app.jar

ENV PORT=3000
ENV TZ=America/Bogota

EXPOSE 3000

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
