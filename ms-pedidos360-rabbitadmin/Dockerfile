# syntax=docker/dockerfile:1
#
# Imagen de ms-pedidos360-rabbitadmin para el despliegue de EP2. Se construye en dos etapas.
#
# 1. Compilacion: Maven compila DENTRO de la imagen, en la EC2. Asi lo que viaja
#    desde el portatil es el codigo fuente -kilobytes- y no el JAR con todas sus
#    dependencias, que pesa decenas de MB. Las dependencias las descarga la EC2,
#    que esta en la red de AWS. Se noto con una subida de 144 MB que en una red
#    domestica lenta iba a tardar 45 minutos.
#
# 2. Ejecucion: solo el JAR sobre un JRE. Maven y el codigo fuente no llegan a
#    la imagen final.

FROM maven:3.9.9-eclipse-temurin-17 AS compilacion
ENV MAVEN_OPTS="-Xmx512m"
WORKDIR /fuente
COPY pom.xml .
COPY src/main src/main
# El cache de ~/.m2 sobrevive entre construcciones: en los despliegues
# siguientes Maven no vuelve a descargar las dependencias. Las pruebas no se
# compilan aqui; se corren antes, en el portatil, con mvn test.
RUN --mount=type=cache,target=/root/.m2     mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre

# Usuario sin privilegios y con UID fijo. Fijo porque el wallet de Oracle se
# monta desde el host con permisos 600 para este UID: si cambiara en cada
# construccion, el proceso dejaria de poder leerlo.
RUN useradd --system --uid 10001 --no-create-home pedidos360

WORKDIR /app
COPY --from=compilacion /fuente/target/ms-pedidos360-rabbitadmin.jar app.jar
USER pedidos360

EXPOSE 8084

# exec reemplaza al shell por la JVM: asi la JVM es el PID 1 y recibe el
# SIGTERM de docker stop, en vez de morir de golpe a los 10 segundos.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
