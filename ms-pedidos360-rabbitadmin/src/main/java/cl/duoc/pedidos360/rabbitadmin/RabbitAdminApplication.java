package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-rabbitadmin: administracion de RabbitMQ separada del negocio.
 *
 * Expone por REST las operaciones de crear, consultar, purgar y eliminar colas,
 * exchanges y bindings, mas las metricas del broker. Esta aparte de orders y de
 * notify a proposito: administrar la topologia y procesar pedidos son dos
 * responsabilidades distintas, con usuarios distintos y riesgos distintos. Un
 * DELETE de cola no debe poder salir del mismo proceso que atiende a clientes.
 */
@SpringBootApplication
public class RabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitAdminApplication.class, args);
    }
}
