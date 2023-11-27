FROM maven:3.5-jdk-8-alpine as BUILD

COPY ldapcollector /usr/src/app/ldapcollector/
COPY ldap_exporter_httpserver /usr/src/app/ldap_exporter_httpserver/
COPY pom.xml /usr/src/app

RUN mvn -B -f /usr/src/app/pom.xml clean package -DskipTests
RUN find /usr/src/app/ldap_exporter_httpserver/

FROM openjdk:8-jre-alpine

WORKDIR /usr/src/app/
COPY --from=BUILD /usr/src/app/ldap_exporter_httpserver/target/ldap_exporter_httpserver-0.4.0-jar-with-dependencies.jar .

ENTRYPOINT ["java", "-jar", "ldap_exporter_httpserver-0.4.0-jar-with-dependencies.jar"]
