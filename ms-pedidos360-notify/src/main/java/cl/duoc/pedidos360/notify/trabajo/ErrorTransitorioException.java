package cl.duoc.pedidos360.notify.trabajo;

/**
 * Falla que podria no repetirse si se vuelve a intentar: el servidor de correo
 * no responde, la impresora de cocina esta ocupada, el generador de documentos
 * devolvio un 503.
 *
 * El consumidor la deja propagar para que Spring Retry reintente con backoff.
 * Solo cuando se agotan los reintentos el mensaje termina en la DLQ.
 */
public class ErrorTransitorioException extends RuntimeException {

    public ErrorTransitorioException(String mensaje) {
        super(mensaje);
    }
}
