package cl.duoc.pedidos360.rabbitadmin.web.dto;

/**
 * Estado de una cola.
 *
 * @param nombre        nombre de la cola
 * @param mensajes      mensajes pendientes de consumir. Un valor que crece de
 *                      forma sostenida indica que el consumidor no da abasto
 * @param consumidores  consumidores conectados. Si es 0 y hay mensajes, nadie
 *                      esta procesando y hay que revisar el microservicio
 */
public record InfoCola(
        String nombre,
        int mensajes,
        int consumidores) {
}
