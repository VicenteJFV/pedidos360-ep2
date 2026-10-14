package cl.duoc.pedidos360.notify.messaging;

import java.time.OffsetDateTime;

/**
 * Mensaje que ms-pedidos360-notify recibe desde RabbitMQ.
 *
 * Es la copia del contrato que publica ms-pedidos360-orders. Esta duplicado a
 * proposito: cada microservicio se despliega por separado y no debe depender de
 * las clases del otro. El acoplamiento entre ambos es el JSON, no un jar comun.
 *
 * Todo lo que el consumidor necesita para trabajar viaja aqui. No consulta la
 * base de datos para completar informacion; si lo hiciera, la mensajeria seria
 * decorativa.
 *
 * @param eventId        identificador unico de este mensaje
 * @param correlationId  identificador del flujo de negocio que lo origino; se
 *                       usa el codigo del pedido, de modo que los tres mensajes
 *                       de un mismo pedido comparten correlacion y se pueden
 *                       seguir juntos en los logs
 * @param tipo           que hay que hacer: EMAIL, KITCHEN o INVOICE
 * @param pedidoId       id del pedido
 * @param codigoPedido   codigo legible del pedido
 * @param estado         estado del pedido al momento de publicar
 * @param clienteEmail   destinatario de la notificacion
 * @param clienteNombre  nombre del cliente
 * @param total          total del pedido
 * @param detalle        descripcion de los items, para el ticket o el documento
 * @param publicadoEn    instante de publicacion
 */
public record ComandoPedido(
        String eventId,
        String correlationId,
        String tipo,
        Long pedidoId,
        String codigoPedido,
        String estado,
        String clienteEmail,
        String clienteNombre,
        String total,
        String detalle,
        OffsetDateTime publicadoEn
) {
}
