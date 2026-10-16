# syntax=docker/dockerfile:1
#
# Imagen del BFF para el despliegue de EP2. La guia exige que el BFF tambien
# corra en la EC2 con Docker; en EP1 era un servicio systemd.
#
# Igual que los microservicios, en dos etapas: Maven compila dentro de la
# imagen, en la EC2, y la imagen final lleva solo el JAR sobre un JRE.

FROM maven:3.9.9-eclipse-temurin-17 AS compilacion
ENV MAVEN_OPTS="-Xmx512m"
WORKDIR /fuente
COPY pom.xml .
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre

RUN useradd --system --uid 10001 --no-create-home pedidos360

WORKDIR /app
COPY --from=compilacion /fuente/target/bff-pedidos360.jar app.jar
USER pedidos360

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
