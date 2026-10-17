FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace/backend
COPY backend/pom.xml ./
COPY backend/.mvn ./.mvn
COPY backend/shared ./shared
COPY backend/booking-service ./booking-service
COPY backend/user-service ./user-service
COPY backend/gateway ./gateway
RUN --mount=type=cache,id=smartoffice-upgrade-m2,target=/root/.m2,sharing=locked mvn -B -ntp -pl user-service -am clean package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system smartoffice && useradd --system --gid smartoffice --home-dir /app smartoffice
WORKDIR /app
RUN mkdir -p /app/logs && chown smartoffice:smartoffice /app/logs
ENV TZ=Asia/Shanghai
COPY --from=build /workspace/backend/user-service/target/smartoffice-user-service-1.0.0.jar /app/smartoffice.jar
USER smartoffice
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/smartoffice.jar"]
