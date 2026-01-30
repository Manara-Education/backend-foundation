# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
# Install Maven manually since wrapper is missing
RUN apk add --no-cache maven
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:25-jdk-alpine
VOLUME /tmp
WORKDIR /app
# Copy the jar from the build stage
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
