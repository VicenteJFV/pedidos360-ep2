package cl.duoc.pedidos360.notify.trabajo;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dominio funcional "notificacion al cliente": lo que consume q.cmd.email.
 *
 * No manda correo de verdad. Mandarlo exigiria un servidor SMTP, credenciales y
 * una casilla real, y nada de eso se evalua en EP2: lo que se evalua es que el
 * mensaje llegue desde RabbitMQ, se procese y se confirme. El trabajo se
 * registra en el log, que es la evidencia que pide la guia.
 */
@Service
public class ServicioNotificaciones {

    private static final Logger log = LoggerFactory.getLogger(ServicioNotificaciones.class);

    private final DisparadorDeFallas disparador;

    public ServicioNotificaciones(DisparadorDeFallas disparador) {
        this.disparador = disparador;
    }

    public void enviar(ComandoPedido comando) {
        disparador.evaluar(comando, "Envio de correo");

        log.info("CORREO ENVIADO | para: {} | pedido: {} | estado: {} | total: {}",
                comando.clienteEmail(), comando.codigoPedido(), comando.estado(), comando.total());
    }
}
