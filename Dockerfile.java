FROM maven:3.9.14-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY scripts/maven-delivery-settings.xml /opt/delivery/maven-settings.xml

COPY src ./src
RUN --mount=type=cache,id=doctrail-maven,target=/root/.m2/repository \
    DOCTRAIL_RUN_MYSQL_IT=false mvn -gs /opt/delivery/maven-settings.xml \
        -s /opt/delivery/maven-settings.xml -B package \
    && mkdir -p /healthcheck/com/doctrail/operations \
    && cp target/classes/com/doctrail/operations/JavaLivenessProbe.class \
        /healthcheck/com/doctrail/operations/JavaLivenessProbe.class

FROM eclipse-temurin:21.0.12.1_1-jre-noble

WORKDIR /app

RUN groupadd --gid 10001 doctrail \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin doctrail \
    && mkdir -p /data/uploads \
    && chown -R 10001:10001 /app /data/uploads

COPY --from=build --chown=10001:10001 /workspace/target/doctrail-0.1.0-SNAPSHOT.jar /app/doctrail.jar
COPY --from=build --chown=10001:10001 /healthcheck /app/healthcheck

USER 10001:10001

CMD ["java", "-jar", "/app/doctrail.jar"]
