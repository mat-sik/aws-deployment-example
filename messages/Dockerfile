# syntax=docker/dockerfile:1.2

# Use Amazon Corretto for the runtime stage
from amazoncorretto:23-alpine

WORKDIR /app

# Copy the pre-compiled JAR file into the container
COPY target/*.jar app.jar

EXPOSE 8080

# Run the JAR file
CMD ["java", "-jar", "app.jar"]
