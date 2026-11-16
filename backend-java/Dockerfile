FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl tzdata

WORKDIR /app

COPY target/backend-java.jar ./app.jar

ENV PORT=3000
ENV TZ=America/Bogota

EXPOSE 3000

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
