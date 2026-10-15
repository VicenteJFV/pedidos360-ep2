package cl.duoc.pedidos360.notify.consumer;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import cl.duoc.pedidos360.notify.messaging.RabbitMQConfig;
import cl.duoc.pedidos360.notify.trabajo.ErrorPermanenteException;
import cl.duoc.pedidos360.notify.trabajo.ErrorTransitorioException;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Logica de ACK, NACK y reintentos compartida por los tres consumidores.
 *
 * Esta aqui y no repetida en cada @RabbitListener porque la decision de cuando
 * confirmar un mensaje es una sola para todo el microservicio, y porque un ACK
 * de mas o de menos es un error silencioso y caro: si se confirma un mensaje
 * que no se proceso, el trabajo se pierde sin que nadie se entere.
 *
 * Las tres rutas posibles:
 *
 *   exito              basicAck(deliveryTag, false)   el mensaje sale de la cola
 *   error permanente   basicNack(deliveryTag, false, false)  va de inmediato a la DLQ
 *   error transitorio  reintentos de 1s, 2s y 4s, y luego basicNack a la DLQ
 *
 * El segundo parametro de basicAck y el de basicNack son multiple=false: se
 * confirma o rechaza exactamente esta entrega y no todas las anteriores del
 * canal. Con multiple=true un solo fallo podria arrastrar mensajes ajenos.
 *
 * El tercer parametro de basicNack es requeue=false. Con requeue=true el
 * mensaje volveria a la misma cola, se volveria a entregar, volveria a fallar
 * por la misma razon y quedaria girando para siempre, bloqueando a los que
 * vienen detras. Con false, RabbitMQ lo publica en cmd.dead.dlx y termina en la
 * DLQ, que es la ruta de recuperacion.
 *
 * POR QUE EL REINTENTO ES PROPIO Y NO spring.rabbitmq.listener.simple.retry
 *
 * Las guias de la asignatura activan los reintentos por propiedades y dejan que
 * RejectAndDontRequeueRecoverer rechace el mensaje al agotarlos. Eso funciona
 * con confirmacion automatica, pero no con acknowledge-mode=manual, y aqui la
 * confirmacion es manual porque la guia de EP2 lo exige.
 *
 * El motivo es concreto. Al agotar los reintentos, el recoverer lanza
 * AmqpRejectAndDontRequeueException, pero el manejador de errores del
 * contenedor la envuelve en ListenerExecutionFailedException, y en modo manual
 * el contenedor solo rechaza por su cuenta cuando la excepcion que recibe ES de
 * rechazo, no cuando viene envuelta. El mensaje entonces no se confirma ni se
 * rechaza: queda colgado en Unacked. Y con prefetch=1 eso detiene la cola
 * completa detras de ese unico mensaje.
 *
 * No es una suposicion: se probo contra el cluster con la configuracion de las
 * guias y la cola quedo en Unacked=1 y Ready=1 de forma indefinida, con la DLQ
 * vacia. Por eso los reintentos se hacen aqui, donde el mismo codigo que
 * reintenta es el que despues rechaza, y el mensaje no puede quedar en limbo.
 */
@Component
public class EjecutorDeConsumo {

    private static final Logger log = LoggerFactory.getLogger(EjecutorDeConsumo.class);

    private final PoliticaDeReintentos politica;

    public EjecutorDeConsumo(PoliticaDeReintentos politica) {
        this.politica = politica;
    }

    /**
     * Las etiquetas del log -Queue, Routing Key, eventId, correlationId- son las
     * que la guia de prueba empirica pide mostrar en pantalla durante la
     * presentacion, escritas con esas mismas palabras. El procesamiento exitoso y
     * el ACK se registran en dos lineas separadas porque son los pasos 4 y 5 de
     * esa guia, y conviene poder senalar cada uno.
     *
     * @param cola         cola desde la que se recibio
     * @param routingKey   clave con la que el mensaje llego a la cola
     * @param comando      mensaje ya deserializado
     * @param canal        canal AMQP, necesario para confirmar a mano
     * @param deliveryTag  identificador de la entrega dentro de ese canal
     * @param trabajo      procesamiento real del dominio
     */
    public void ejecutar(String cola, String routingKey, ComandoPedido comando,
                         Channel canal, long deliveryTag, Runnable trabajo) throws IOException {

        log.info("MENSAJE RECIBIDO | Queue: {} | Routing Key: {} | tipo: {} | pedido: {} | eventId: {} | correlationId: {} | Delivery Tag: {}",
                cola, routingKey, comando.tipo(), comando.codigoPedido(),
                comando.eventId(), comando.correlationId(), deliveryTag);

        int total = politica.intentosTotales();

        for (int intento = 1; intento <= total; intento++) {
            try {
                trabajo.run();

                log.info("PROCESAMIENTO EXITOSO | Queue: {} | Routing Key: {} | eventId: {} | pedido: {} | intento {} de {}",
                        cola, routingKey, comando.eventId(), comando.codigoPedido(), intento, total);

                canal.basicAck(deliveryTag, false);
                log.info("ACK MANUAL ENVIADO | Queue: {} | eventId: {} | Delivery Tag: {}",
                        cola, comando.eventId(), deliveryTag);
                return;

            } catch (ErrorPermanenteException e) {
                log.error("ERROR NO RECUPERABLE | Queue: {} | eventId: {} | motivo: {} | no se reintenta",
                        cola, comando.eventId(), e.getMessage());
                rechazar(cola, comando, canal, deliveryTag);
                return;

            } catch (ErrorTransitorioException e) {
                if (intento == total) {
                    log.error("REINTENTOS AGOTADOS | Queue: {} | eventId: {} | {} intentos | motivo: {}",
                            cola, comando.eventId(), total, e.getMessage());
                    rechazar(cola, comando, canal, deliveryTag);
                    return;
                }

                Duration espera = politica.esperaTrasIntento(intento);
                log.warn("ERROR RECUPERABLE | Queue: {} | eventId: {} | intento {} de {} | motivo: {} | reintento en {} ms",
                        cola, comando.eventId(), intento, total, e.getMessage(), espera.toMillis());

                if (!esperar(espera)) {
                    log.error("REINTENTO INTERRUMPIDO | Queue: {} | eventId: {} | se rechaza para no dejarlo sin confirmar",
                            cola, comando.eventId());
                    rechazar(cola, comando, canal, deliveryTag);
                    return;
                }

            } catch (RuntimeException e) {
                // Cualquier otra excepcion es un defecto del codigo, no una falla
                // del entorno: reintentarla daria el mismo resultado. Se rechaza y
                // se deja la traza completa, para que la DLQ tenga el mensaje y el
                // log tenga la causa.
                log.error("ERROR INESPERADO | Queue: {} | eventId: {} | se rechaza sin reintentar",
                        cola, comando.eventId(), e);
                rechazar(cola, comando, canal, deliveryTag);
                return;
            }
        }
    }

    private void rechazar(String cola, ComandoPedido comando, Channel canal, long deliveryTag)
            throws IOException {

        canal.basicNack(deliveryTag, false, false);

        log.error("NACK SIN REQUEUE ENVIADO | Queue: {} | eventId: {} | el mensaje sale por {} hacia {}",
                cola, comando.eventId(), RabbitMQConfig.EXCHANGE_DLX, RabbitMQConfig.dlqDe(cola));
    }

    /** Devuelve false si la espera se interrumpio y hay que abandonar el reintento. */
    private boolean esperar(Duration espera) {
        try {
            Thread.sleep(espera.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
