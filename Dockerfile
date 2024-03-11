FROM amazoncorretto:21-alpine3.19
COPY ./target/rinha4-1.0-SNAPSHOT.jar /tmp
WORKDIR /tmp
ENTRYPOINT ["java","-jar", "rinha4-1.0-SNAPSHOT.jar"]