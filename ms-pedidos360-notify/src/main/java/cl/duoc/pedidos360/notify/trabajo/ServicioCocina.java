package cl.duoc.pedidos360.notify.trabajo;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dominio funcional "ticket de cocina": lo que consume q.cmd.kitchen.
 *
 * Imprime el ticket en el log con el detalle que viene en el mensaje. Es a
 * proposito que el detalle venga en el mensaje y no se consulte a la base: la
 * guia exige que el consumidor reciba el dato desde RabbitMQ y no lo complete
 * contra Oracle, porque entonces la mensajeria seria un adorno.
 */
@Service
public class ServicioCocina {

    private static final Logger log = LoggerFactory.getLogger(ServicioCocina.class);

    private final DisparadorDeFallas disparador;

    public ServicioCocina(DisparadorDeFallas disparador) {
        this.disparador = disparador;
    }

    public void imprimirTicket(ComandoPedido comando) {
        disparador.evaluar(comando, "Impresion de ticket");

        log.info("TICKET DE COCINA | pedido: {} | cliente: {} | items: {}",
                comando.codigoPedido(), comando.clienteNombre(), comando.detalle());
    }
}
