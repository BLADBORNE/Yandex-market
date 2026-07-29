FROM amazoncorretto:21-alpine-jdk
COPY build/libs/market-app.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
