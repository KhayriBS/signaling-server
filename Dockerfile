# Use a lightweight OpenJDK image
FROM eclipse-temurin:17-jdk-jammy AS build

# Set working directory
WORKDIR /app

# Copy Maven wrapper and pom.xml
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn

# Ensure wrapper is executable (Linux containers)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY src src

# Build the application (skip tests for faster image build)
RUN ./mvnw clean package -DskipTests

# Second stage: run the jar in a smaller JRE image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built jar from the previous stage
COPY --from=build /app/target/remote-it-support-server-0.0.1-SNAPSHOT.jar app.jar

# Expose the application port (matches server.port in application.properties)
EXPOSE 8080

# Set active profile via environment variable if needed
# ENV SPRING_PROFILES_ACTIVE=prod

# Run the application
ENTRYPOINT ["java","-jar","/app/app.jar"]

