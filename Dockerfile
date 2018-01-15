FROM maven:3.5-jdk-8-alpine as BUILD

COPY collector /usr/src/app/collector/
COPY ldap_exporter_httpserver /usr/src/app/ldap_exporter_httpserver/
COPY pom.xml /usr/src/app

RUN mvn -B -f /usr/src/app/pom.xml clean package -DskipTests

FROM openjdk:8-jre-alpine

WORKDIR /usr/src/app/
COPY --from=BUILD /usr/src/app/ldap_exporter_httpserver/target/ldap_exporter_httpserver-0.1.0-SNAPSHOT-jar-with-dependencies.jar .

ENTRYPOINT ["java", "-jar", "ldap_exporter_httpserver-0.1.0-SNAPSHOT-jar-with-dependencies.jar"]
