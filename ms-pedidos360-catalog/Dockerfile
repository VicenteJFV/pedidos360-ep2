# Imagen de ms-pedidos360-catalog para el despliegue de EP2.
#
# Solo empaqueta el JAR ya compilado. La compilacion ocurre antes, con Maven,
# y no dentro de la imagen: asi la EC2 no necesita Maven ni descargar
# dependencias, y la imagen pesa lo que pesa el JRE mas el JAR.
FROM eclipse-temurin:17-jre

# Usuario sin privilegios y con UID fijo. Fijo porque el wallet de Oracle se
# monta desde el host con permisos 600 para este UID: si cambiara en cada
# construccion, el proceso dejaria de poder leerlo.
RUN useradd --system --uid 10001 --no-create-home pedidos360

WORKDIR /app
COPY target/ms-pedidos360-catalog.jar app.jar
USER pedidos360

EXPOSE 8082

# exec reemplaza al shell por la JVM: asi la JVM es el PID 1 y recibe el
# SIGTERM de docker stop, en vez de morir de golpe a los 10 segundos.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
