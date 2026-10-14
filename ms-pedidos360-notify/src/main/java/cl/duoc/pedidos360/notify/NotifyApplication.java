package cl.duoc.pedidos360.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ms-pedidos360-notify: consumidor RabbitMQ del Caso 0 - Pedidos360.
 *
 * Su unica entrada de trabajo son las tres colas del dominio. No expone API de
 * negocio y no tiene base de datos: todo lo que necesita para procesar viene en
 * el mensaje.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
