# Builds and runs the shop app in one container (used by Render, works on any Docker host).
# Stage 1: build the jar with Maven.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 2: small Java runtime with only the jar.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/csms.jar app.jar
# Render free plan has 512 MB RAM: keep the Java heap inside it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -Duser.timezone=Asia/Kolkata"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
