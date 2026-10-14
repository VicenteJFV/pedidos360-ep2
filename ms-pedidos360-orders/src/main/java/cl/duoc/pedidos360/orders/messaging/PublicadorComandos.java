package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.domain.Pedido;
import cl.duoc.pedidos360.orders.domain.PedidoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Productor de mensajes de ms-pedidos360-orders.
 *
 * Publica hacia cmd.direct o cmd.topic usando las routing keys del caso. La
 * publicacion ocurre siempre despues de una accion valida del dominio, nunca
 * "a mano": el flujo de la prueba empirica empieza en una operacion real del
 * sistema.
 *
 * El log de cada publicacion incluye eventId, correlationId, exchange y routing
 * key, que son los campos que la guia de prueba empirica pide mostrar en
 * terminal durante la presentacion.
 */
@Component
public class PublicadorComandos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorComandos.class);

    private final RabbitTemplate rabbitTemplate;

    public PublicadorComandos(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Notificacion al cliente. Se publica por cmd.direct con la routing key
     * email.send, que coincide de forma exacta con el binding de q.cmd.email.
     */
    public void publicarEmail(Pedido pedido) {
        publicar(RabbitMQConfig.EXCHANGE_DIRECT, RabbitMQConfig.RK_EMAIL_SEND,
                construir(pedido, "EMAIL"));
    }

    /**
     * Ticket de cocina. Se publica por cmd.topic con la routing key
     * kitchen.ticket.new, que calza con el patron kitchen.# del binding.
     *
     * Este flujo usa topic a proposito: demuestra el segundo tipo de exchange
     * con una clave que no es una coincidencia exacta sino un patron.
     */
    public void publicarKitchen(Pedido pedido) {
        publicar(RabbitMQConfig.EXCHANGE_TOPIC, RabbitMQConfig.RK_KITCHEN_TICKET + ".new",
                construir(pedido, "KITCHEN"));
    }

    /**
     * Generacion del documento. Se publica por cmd.direct con invoice.gen.
     */
    public void publicarInvoice(Pedido pedido) {
        publicar(RabbitMQConfig.EXCHANGE_DIRECT, RabbitMQConfig.RK_INVOICE_GEN,
                construir(pedido, "INVOICE"));
    }

    private void publicar(String exchange, String routingKey, ComandoPedido comando) {
        log.info("Publicando mensaje | Exchange: {} | Routing Key: {} | eventId: {} | correlationId: {}",
                exchange, routingKey, comando.eventId(), comando.correlationId());

        // El CorrelationData lleva el eventId hasta el ConfirmCallback: cuando el
        // broker responde ack o nack, el log puede decir de que mensaje habla.
        rabbitTemplate.convertAndSend(exchange, routingKey, comando,
                new CorrelationData(comando.eventId()));

        log.info("Mensaje publicado | tipo: {} | pedido: {}", comando.tipo(), comando.codigoPedido());
    }

    private ComandoPedido construir(Pedido pedido, String tipo) {
        String detalle = pedido.getItems().stream()
                .map(this::describir)
                .collect(Collectors.joining(", "));

        return new ComandoPedido(
                UUID.randomUUID().toString(),
                pedido.getCodigo(),
                tipo,
                pedido.getId(),
                pedido.getCodigo(),
                pedido.getEstado().name(),
                pedido.getClienteEmail(),
                pedido.getClienteNombre(),
                pedido.getTotal().toPlainString(),
                detalle,
                OffsetDateTime.now()
        );
    }

    private String describir(PedidoItem item) {
        return item.getNombreProducto() + " x" + item.getCantidad();
    }
}
