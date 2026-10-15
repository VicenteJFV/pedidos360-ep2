package cl.duoc.pedidos360.notify.consumer;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import cl.duoc.pedidos360.notify.messaging.RabbitMQConfig;
import cl.duoc.pedidos360.notify.trabajo.ServicioDocumentos;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Consumidor del dominio funcional de documentos de venta: q.cmd.invoice. */
@Component
public class ConsumidorInvoice {

    private final ServicioDocumentos documentos;
    private final EjecutorDeConsumo ejecutor;

    public ConsumidorInvoice(ServicioDocumentos documentos, EjecutorDeConsumo ejecutor) {
        this.documentos = documentos;
        this.ejecutor = ejecutor;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_INVOICE)
    public void consumirInvoice(ComandoPedido comando,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                                Channel canal,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        ejecutor.ejecutar(RabbitMQConfig.QUEUE_INVOICE, routingKey, comando, canal, deliveryTag,
                () -> documentos.generar(comando));
    }
}
