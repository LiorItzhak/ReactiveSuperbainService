FROM openjdk:8u252-jre-slim-buster

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

COPY client.keystore.p12 .
COPY client.truststore.jks .

CMD ["java", "-jar", "app.jar"]
