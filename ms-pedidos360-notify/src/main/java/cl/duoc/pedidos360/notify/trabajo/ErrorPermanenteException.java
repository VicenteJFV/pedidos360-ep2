package cl.duoc.pedidos360.notify.trabajo;

/**
 * Falla que va a repetirse igual cuantas veces se intente: la direccion de
 * correo no existe, el mensaje viene incompleto, el pedido no trae items.
 *
 * El consumidor NO la reintenta. Reintentar cuatro veces un mensaje que nunca
 * va a poder procesarse solo gasta tiempo del consumidor y retrasa a los
 * mensajes que si se pueden procesar: se rechaza de inmediato con NACK sin
 * requeue y se manda a la DLQ para que alguien lo revise.
 */
public class ErrorPermanenteException extends RuntimeException {

    public ErrorPermanenteException(String mensaje) {
        super(mensaje);
    }
}
