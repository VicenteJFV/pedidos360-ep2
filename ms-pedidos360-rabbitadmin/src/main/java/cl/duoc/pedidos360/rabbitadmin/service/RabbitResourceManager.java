package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.config.NombresDelCaso;
import cl.duoc.pedidos360.rabbitadmin.web.dto.InfoCola;
import cl.duoc.pedidos360.rabbitadmin.web.dto.MetricasBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsula RabbitAdmin: es el unico lugar del microservicio que habla con el
 * broker.
 *
 * El controlador valida y traduce a HTTP; este servicio ejecuta. La guia de EP2
 * lo pide asi, y la razon practica es que el dia que haya que registrar una
 * auditoria de quien elimino una cola, hay un solo sitio donde ponerla.
 */
@Service
public class RabbitResourceManager {

    private static final Logger log = LoggerFactory.getLogger(RabbitResourceManager.class);

    private final RabbitAdmin rabbitAdmin;

    public RabbitResourceManager(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    // ---------------------------------------------------------------------
    // Colas
    // ---------------------------------------------------------------------

    /**
     * Declara una cola duradera. Declarar es idempotente en AMQP: si la cola ya
     * existe con los mismos argumentos, no pasa nada. Si existe con argumentos
     * distintos, el broker responde PRECONDITION_FAILED, que es la proteccion
     * que evita que esta API pise la topologia del caso por accidente.
     */
    public void crearCola(String nombre) {
        rabbitAdmin.declareQueue(new Queue(nombre, true, false, false));
        log.info("Cola declarada | nombre: {}", nombre);
    }

    /**
     * @throws RecursoNoEncontradoException si la cola no existe
     */
    public InfoCola consultarCola(String nombre) {
        QueueInformation info = rabbitAdmin.getQueueInfo(nombre);
        if (info == null) {
            throw new RecursoNoEncontradoException("La cola " + nombre + " no existe en el broker");
        }
        return new InfoCola(info.getName(), info.getMessageCount(), info.getConsumerCount());
    }

    /**
     * Elimina la cola. Es idempotente: eliminar una cola que no existe no es un
     * error y no informa nada distinto.
     *
     * No devuelve si la cola existia, aunque RabbitAdmin.deleteQueue devuelva un
     * boolean, porque ese boolean no significa eso: se comprobo contra el cluster
     * que devuelve true tambien cuando la cola nunca estuvo. Para saber si una
     * cola existe hay que consultarla, que es lo que hace consultarCola.
     */
    public void eliminarCola(String nombre) {
        rabbitAdmin.deleteQueue(nombre);
        log.info("Cola eliminada | nombre: {}", nombre);
    }

    /**
     * Vacia la cola sin eliminarla. Sirve, por ejemplo, cuando una DLQ ya fue
     * revisada: esos mensajes dejaron de ser informacion y pasaron a ser ruido
     * en las metricas.
     *
     * @return cuantos mensajes se descartaron
     */
    public int purgarCola(String nombre) {
        if (rabbitAdmin.getQueueInfo(nombre) == null) {
            throw new RecursoNoEncontradoException("La cola " + nombre + " no existe en el broker");
        }
        // purgeQueue(nombre) y no purgeQueue(nombre, false): la variante de dos
        // parametros devuelve void, y aqui interesa saber cuantos mensajes se
        // descartaron para poder informarlo en la respuesta.
        int purgados = rabbitAdmin.purgeQueue(nombre);
        log.info("Cola purgada | nombre: {} | mensajes descartados: {}", nombre, purgados);
        return purgados;
    }

    // ---------------------------------------------------------------------
    // Exchanges
    // ---------------------------------------------------------------------

    public void crearExchange(String nombre, TipoExchange tipo) {
        Exchange exchange = switch (tipo) {
            case DIRECT -> ExchangeBuilder.directExchange(nombre).durable(true).build();
            case TOPIC -> ExchangeBuilder.topicExchange(nombre).durable(true).build();
            case FANOUT -> ExchangeBuilder.fanoutExchange(nombre).durable(true).build();
            case HEADERS -> ExchangeBuilder.headersExchange(nombre).durable(true).build();
        };
        rabbitAdmin.declareExchange(exchange);
        log.info("Exchange declarado | nombre: {} | tipo: {}", nombre, tipo);
    }

    /**
     * Elimina el exchange. Igual que con las colas, es idempotente: AMQP define
     * exchange.delete como una operacion que no falla si el exchange no esta.
     */
    public void eliminarExchange(String nombre) {
        rabbitAdmin.deleteExchange(nombre);
        log.info("Exchange eliminado | nombre: {}", nombre);
    }

    // ---------------------------------------------------------------------
    // Bindings
    // ---------------------------------------------------------------------

    /**
     * El Binding se construye directamente, y no con
     * BindingBuilder.bind(cola).to(new DirectExchange(nombre)) como aparece en
     * los ejemplos, porque el tipo del exchange local nunca viaja al broker: un
     * binding lleva nombre de exchange, destino y routing key, nada mas. Escribir
     * DirectExchange para enlazar una cola a cmd.topic funcionaria igual, pero
     * dejaria el codigo afirmando algo que no es cierto.
     */
    public void crearBinding(String cola, String exchange, String routingKey) {
        rabbitAdmin.declareBinding(binding(cola, exchange, routingKey));
        log.info("Binding declarado | cola: {} | exchange: {} | routing key: {}",
                cola, exchange, routingKey);
    }

    /**
     * Para eliminar un binding hay que reconstruirlo igual que se creo: cola,
     * exchange y routing key deben coincidir. AMQP no tiene un identificador de
     * binding, porque el binding ES esa combinacion.
     */
    public void eliminarBinding(String cola, String exchange, String routingKey) {
        rabbitAdmin.removeBinding(binding(cola, exchange, routingKey));
        log.info("Binding eliminado | cola: {} | exchange: {} | routing key: {}",
                cola, exchange, routingKey);
    }

    private Binding binding(String cola, String exchange, String routingKey) {
        return new Binding(cola, Binding.DestinationType.QUEUE, exchange, routingKey, null);
    }

    // ---------------------------------------------------------------------
    // Metricas
    // ---------------------------------------------------------------------

    /**
     * Las metricas se obtienen preguntando por cada cola conocida, y no listando
     * el broker completo, porque RabbitAdmin trabaja sobre AMQP y AMQP no tiene
     * una operacion para listar colas: eso solo lo ofrece la API de Management,
     * que exigiria credenciales HTTP aparte. Preguntar por nombre alcanza, porque
     * las colas del caso son seis y estan definidas.
     */
    public MetricasBroker metricas() {
        List<InfoCola> funcionales = consultarTodas(NombresDelCaso.COLAS_FUNCIONALES);
        List<InfoCola> muertas = consultarTodas(NombresDelCaso.COLAS_MUERTAS);

        int pendientes = funcionales.stream().mapToInt(InfoCola::mensajes).sum();
        int consumidores = funcionales.stream().mapToInt(InfoCola::consumidores).sum();
        int enDlq = muertas.stream().mapToInt(InfoCola::mensajes).sum();

        int total = pendientes + enDlq;
        double tasa = total == 0 ? 0.0 : (double) enDlq / total;

        int esperadas = NombresDelCaso.COLAS_FUNCIONALES.size()
                + NombresDelCaso.COLAS_MUERTAS.size();

        return new MetricasBroker(
                funcionales.size() + muertas.size(),
                esperadas,
                pendientes,
                consumidores,
                enDlq,
                Math.round(tasa * 10000.0) / 10000.0,
                funcionales,
                muertas);
    }

    /** Omite las colas que no existen: colasActivas debe reflejar la realidad. */
    private List<InfoCola> consultarTodas(List<String> nombres) {
        List<InfoCola> encontradas = new ArrayList<>();
        for (String nombre : nombres) {
            QueueInformation info = rabbitAdmin.getQueueInfo(nombre);
            if (info != null) {
                encontradas.add(new InfoCola(
                        info.getName(), info.getMessageCount(), info.getConsumerCount()));
            }
        }
        return encontradas;
    }
}
