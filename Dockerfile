FROM eclipse-temurin:21-jre

WORKDIR /app

RUN addgroup --system app && adduser --system --ingroup app app

ARG JAR_FILE=build/libs/asset-sync-service-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
