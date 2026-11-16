FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

COPY pom.xml ./
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine AS runner

RUN apk add --no-cache curl tzdata

WORKDIR /app

COPY --from=builder /app/target/backend-java.jar ./app.jar

ENV PORT=3000
ENV TZ=America/Bogota

EXPOSE 3000

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
