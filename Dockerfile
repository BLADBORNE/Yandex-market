FROM amazoncorretto:21-alpine-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY openapi openapi
COPY market-app/build.gradle market-app/build.gradle
COPY payment-service/build.gradle payment-service/build.gradle

RUN ./gradlew :market-app:dependencies :payment-service:dependencies --no-daemon

COPY market-app/src market-app/src
COPY payment-service/src payment-service/src

RUN ./gradlew :market-app:bootJar :payment-service:bootJar --no-daemon

FROM amazoncorretto:21-alpine-jdk AS market-app
WORKDIR /app
COPY --from=build /workspace/market-app/build/libs/market-app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM amazoncorretto:21-alpine-jdk AS payment-service
WORKDIR /app
COPY --from=build /workspace/payment-service/build/libs/payment-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
