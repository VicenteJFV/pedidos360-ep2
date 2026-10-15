package cl.duoc.pedidos360.notify.consumer;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import cl.duoc.pedidos360.notify.messaging.RabbitMQConfig;
import cl.duoc.pedidos360.notify.trabajo.ServicioCocina;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumidor del dominio funcional de cocina: q.cmd.kitchen.
 *
 * Esta cola recibe por cmd.topic con el patron kitchen.#, asi que le llegan
 * tanto kitchen.ticket.new como cualquier otra clave que empiece con kitchen.
 * Al consumidor le resulta indiferente: su contrato es la cola, no el exchange
 * ni la routing key con que el mensaje llego a ella.
 */
@Component
public class ConsumidorKitchen {

    private final ServicioCocina cocina;
    private final EjecutorDeConsumo ejecutor;

    public ConsumidorKitchen(ServicioCocina cocina, EjecutorDeConsumo ejecutor) {
        this.cocina = cocina;
        this.ejecutor = ejecutor;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_KITCHEN)
    public void consumirKitchen(ComandoPedido comando,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                                Channel canal,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        ejecutor.ejecutar(RabbitMQConfig.QUEUE_KITCHEN, routingKey, comando, canal, deliveryTag,
                () -> cocina.imprimirTicket(comando));
    }
}
