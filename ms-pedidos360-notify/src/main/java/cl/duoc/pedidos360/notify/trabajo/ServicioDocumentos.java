package cl.duoc.pedidos360.notify.trabajo;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dominio funcional "documento de venta": lo que consume q.cmd.invoice.
 *
 * Genera el numero de documento y lo registra. No produce un PDF: igual que en
 * los otros dos flujos, lo que se demuestra es el recorrido del mensaje.
 */
@Service
public class ServicioDocumentos {

    private static final Logger log = LoggerFactory.getLogger(ServicioDocumentos.class);

    private final DisparadorDeFallas disparador;

    public ServicioDocumentos(DisparadorDeFallas disparador) {
        this.disparador = disparador;
    }

    public void generar(ComandoPedido comando) {
        disparador.evaluar(comando, "Generacion de documento");

        log.info("DOCUMENTO GENERADO | pedido: {} | cliente: {} | total: {} | documento: BOL-{}",
                comando.codigoPedido(), comando.clienteNombre(), comando.total(), comando.pedidoId());
    }
}
