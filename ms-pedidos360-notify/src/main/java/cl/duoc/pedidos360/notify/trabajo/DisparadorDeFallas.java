package cl.duoc.pedidos360.notify.trabajo;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Decide si el procesamiento de un mensaje debe fallar, y de que forma.
 *
 * La prueba de error controlado que exige la guia tiene que poder ejecutarse en
 * vivo durante la presentacion sin tocar codigo, sin reiniciar servicios y sin
 * apagar contenedores. Por eso el disparador es un dato de entrada: el correo
 * del cliente del pedido. Se crea el pedido con ese correo, se lo acepta, y el
 * consumidor falla solo para ese mensaje mientras los demas siguen pasando.
 *
 *   error-transitorio@...  falla recuperable  reintentos 1s -> 2s -> 4s  DLQ
 *   error-permanente@...   falla definitiva   NACK inmediato            DLQ
 *
 * Tambien valida el mensaje antes de procesarlo: un comando sin correo o sin
 * detalle no se puede ejecutar y no tiene sentido reintentarlo.
 */
@Component
public class DisparadorDeFallas {

    /** Correo que provoca una falla recuperable, para demostrar retry + backoff. */
    public static final String MARCA_TRANSITORIA = "error-transitorio";

    /** Correo que provoca una falla definitiva, para demostrar NACK inmediato. */
    public static final String MARCA_PERMANENTE = "error-permanente";

    /**
     * @throws ErrorPermanenteException  si el mensaje no se puede procesar nunca
     * @throws ErrorTransitorioException si la falla podria no repetirse
     */
    public void evaluar(ComandoPedido comando, String tarea) {
        if (!StringUtils.hasText(comando.clienteEmail())) {
            throw new ErrorPermanenteException(
                    "El comando llego sin clienteEmail; no hay a quien notificar");
        }
        if (!StringUtils.hasText(comando.codigoPedido())) {
            throw new ErrorPermanenteException(
                    "El comando llego sin codigoPedido; no se puede identificar el pedido");
        }

        String correo = comando.clienteEmail().toLowerCase();

        if (correo.contains(MARCA_PERMANENTE)) {
            throw new ErrorPermanenteException(
                    tarea + ": la direccion " + comando.clienteEmail()
                            + " no existe. Reintentar no va a cambiar el resultado");
        }
        if (correo.contains(MARCA_TRANSITORIA)) {
            throw new ErrorTransitorioException(
                    tarea + ": el proveedor no responde para " + comando.clienteEmail()
                            + ". Puede funcionar en el siguiente intento");
        }
    }
}
