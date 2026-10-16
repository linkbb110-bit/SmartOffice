FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn ./.mvn
COPY shared ./shared
COPY backend ./backend
COPY user-service ./user-service
COPY gateway ./gateway
RUN --mount=type=cache,id=smartoffice-upgrade-m2,target=/root/.m2,sharing=locked mvn -B -ntp -pl gateway -am clean package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system smartoffice && useradd --system --gid smartoffice --home-dir /app smartoffice
WORKDIR /app
RUN mkdir -p /app/logs && chown smartoffice:smartoffice /app/logs
ENV TZ=Asia/Shanghai
COPY --from=build /workspace/gateway/target/smartoffice-gateway-1.0.0.jar /app/smartoffice.jar
USER smartoffice
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/smartoffice.jar"]
