package cl.duoc.pedidos360.notify.consumer;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import cl.duoc.pedidos360.notify.messaging.RabbitMQConfig;
import cl.duoc.pedidos360.notify.trabajo.ServicioNotificaciones;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumidor del dominio funcional de notificaciones: q.cmd.email.
 *
 * Hay una clase por dominio funcional en vez de un unico consumidor con tres
 * metodos, para que cada flujo se pueda leer, probar y -si hiciera falta-
 * desactivar por separado.
 */
@Component
public class ConsumidorEmail {

    private final ServicioNotificaciones notificaciones;
    private final EjecutorDeConsumo ejecutor;

    public ConsumidorEmail(ServicioNotificaciones notificaciones, EjecutorDeConsumo ejecutor) {
        this.notificaciones = notificaciones;
        this.ejecutor = ejecutor;
    }

    /**
     * Recibe el Channel y el DELIVERY_TAG porque la confirmacion es manual: sin
     * el canal no se puede hacer basicAck, y sin el tag no se sabria cual de las
     * entregas del canal se esta confirmando.
     *
     * El primer parametro llega ya deserializado a ComandoPedido por el
     * convertidor JSON.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_EMAIL)
    public void consumirEmail(ComandoPedido comando,
                              Channel canal,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        ejecutor.ejecutar(RabbitMQConfig.QUEUE_EMAIL, comando, canal, deliveryTag,
                () -> notificaciones.enviar(comando));
    }
}
