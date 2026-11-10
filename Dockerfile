# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# dmn-storage/ is not baked into the image: the service seeds it from the packaged model on
# first start. Mount a volume there to keep rule edits across restarts.
VOLUME ["/app/dmn-storage"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
