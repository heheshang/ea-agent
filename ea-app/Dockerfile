# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY ea-common/pom.xml ea-common/pom.xml
COPY ea-api/pom.xml ea-api/pom.xml
COPY ea-ontology/pom.xml ea-ontology/pom.xml
COPY ea-channel/pom.xml ea-channel/pom.xml
COPY ea-agent/pom.xml ea-agent/pom.xml
COPY ea-app/pom.xml ea-app/pom.xml
COPY ea-common ea-common
COPY ea-api ea-api
COPY ea-ontology ea-ontology
COPY ea-channel ea-channel
COPY ea-agent ea-agent
COPY ea-app ea-app
RUN mvn -q -B package -pl ea-app -am -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/ea-app/target/ea-app-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]