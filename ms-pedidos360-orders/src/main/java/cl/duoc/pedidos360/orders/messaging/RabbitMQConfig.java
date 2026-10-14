package cl.duoc.pedidos360.orders.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia RabbitMQ del Caso 0 - Pedidos360.
 *
 * Todos los nombres de exchanges, colas, DLQ y routing keys viven aqui y en
 * ningun otro lugar: la logica de negocio nunca escribe una cadena literal.
 * Son ademas los nombres exactos definidos por la guia de EP2 y no deben
 * cambiarse, porque la presentacion se evalua sobre ellos.
 *
 *   Cola             Proposito                 DLQ                  direct           topic
 *   q.cmd.email      Envio de correos/push     q.cmd.email.dlq      email.send       email.*
 *   q.cmd.kitchen    Ticket de cocina          q.cmd.kitchen.dlq    kitchen.ticket   kitchen.#
 *   q.cmd.invoice    Generacion de documento   q.cmd.invoice.dlq    invoice.gen      invoice.*
 *
 * Cada cola funcional se declara con dos argumentos que la conectan a su DLQ:
 * x-dead-letter-exchange apunta a cmd.dead.dlx y x-dead-letter-routing-key
 * indica con que clave se reencola el mensaje muerto. Cuando el consumidor
 * rechaza un mensaje sin requeue, RabbitMQ lo publica automaticamente en ese
 * exchange y termina en la DLQ correspondiente.
 *
 * Esta misma configuracion se declara tambien en ms-pedidos360-notify. Las
 * declaraciones deben coincidir exactamente -durabilidad y argumentos- o
 * RabbitMQ rechaza la segunda con PRECONDITION_FAILED - inequivalent arg.
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    // ---------------------------------------------------------------------
    // Exchanges comunes del caso
    // ---------------------------------------------------------------------

    /** Coincidencia exacta de routing key. */
    public static final String EXCHANGE_DIRECT = "cmd.direct";

    /** Coincidencia por patron, con comodines * y #. */
    public static final String EXCHANGE_TOPIC = "cmd.topic";

    /** Dead Letter Exchange: recibe los mensajes que no se pudieron procesar. */
    public static final String EXCHANGE_DLX = "cmd.dead.dlx";

    // ---------------------------------------------------------------------
    // Colas funcionales y sus DLQ
    // ---------------------------------------------------------------------

    public static final String QUEUE_EMAIL = "q.cmd.email";
    public static final String QUEUE_KITCHEN = "q.cmd.kitchen";
    public static final String QUEUE_INVOICE = "q.cmd.invoice";

    public static final String DLQ_EMAIL = "q.cmd.email.dlq";
    public static final String DLQ_KITCHEN = "q.cmd.kitchen.dlq";
    public static final String DLQ_INVOICE = "q.cmd.invoice.dlq";

    // ---------------------------------------------------------------------
    // Routing keys
    // ---------------------------------------------------------------------

    /** Bindings direct: la clave debe coincidir caracter por caracter. */
    public static final String RK_EMAIL_SEND = "email.send";
    public static final String RK_KITCHEN_TICKET = "kitchen.ticket";
    public static final String RK_INVOICE_GEN = "invoice.gen";

    /**
     * Bindings topic. El comodin * sustituye exactamente una palabra y el #
     * sustituye cero o mas. Por eso email.* alcanza a email.send.high pero no
     * a email.send.high.urgent, mientras que kitchen.# alcanza a ambas.
     */
    public static final String RK_EMAIL_TOPIC = "email.*";
    public static final String RK_KITCHEN_TOPIC = "kitchen.#";
    public static final String RK_INVOICE_TOPIC = "invoice.*";

    /** Claves con las que cada cola reencola hacia el DLX. */
    public static final String RK_EMAIL_DEAD = "email.dead";
    public static final String RK_KITCHEN_DEAD = "kitchen.dead";
    public static final String RK_INVOICE_DEAD = "invoice.dead";

    // ---------------------------------------------------------------------
    // Declaracion de exchanges
    // ---------------------------------------------------------------------

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(EXCHANGE_DIRECT);
    }

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(EXCHANGE_TOPIC);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(EXCHANGE_DLX);
    }

    // ---------------------------------------------------------------------
    // Colas funcionales
    // ---------------------------------------------------------------------

    @Bean
    public Queue colaEmail() {
        return colaFuncional(QUEUE_EMAIL, RK_EMAIL_DEAD);
    }

    @Bean
    public Queue colaKitchen() {
        return colaFuncional(QUEUE_KITCHEN, RK_KITCHEN_DEAD);
    }

    @Bean
    public Queue colaInvoice() {
        return colaFuncional(QUEUE_INVOICE, RK_INVOICE_DEAD);
    }

    /**
     * Una cola funcional: duradera, enlazada a su DLQ y con limites de
     * retencion para que no crezca de forma descontrolada.
     *
     *   x-message-ttl   descarta mensajes que llevan demasiado tiempo sin
     *                   consumirse; aqui 10 minutos, suficiente para una
     *                   notificacion que ya perdio sentido si no se envio.
     *   x-max-length    tope de mensajes en la cola. Al superarlo, el mensaje
     *                   mas antiguo se reencola hacia el DLX en vez de
     *                   perderse en silencio.
     *
     * Sin estos limites, un consumidor caido durante horas hace crecer la cola
     * hasta agotar la memoria del broker.
     */
    private Queue colaFuncional(String nombre, String routingKeyMuerta) {
        return QueueBuilder.durable(nombre)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", routingKeyMuerta)
                .withArgument("x-message-ttl", 600000)
                .withArgument("x-max-length", 10000)
                .withArgument("x-overflow", "reject-publish-dlx")
                .build();
    }

    // ---------------------------------------------------------------------
    // Dead Letter Queues
    // ---------------------------------------------------------------------
    //
    // Las DLQ no llevan x-dead-letter-exchange: un mensaje que ya fallo no
    // debe poder reencolarse otra vez, o se formaria un ciclo. Si llevan TTL,
    // mas largo que el de la cola principal, porque un mensaje muerto debe
    // sobrevivir lo suficiente para que alguien lo revise.

    @Bean
    public Queue dlqEmail() {
        return colaMuerta(DLQ_EMAIL);
    }

    @Bean
    public Queue dlqKitchen() {
        return colaMuerta(DLQ_KITCHEN);
    }

    @Bean
    public Queue dlqInvoice() {
        return colaMuerta(DLQ_INVOICE);
    }

    private Queue colaMuerta(String nombre) {
        return QueueBuilder.durable(nombre)
                .withArgument("x-message-ttl", 86400000)
                .withArgument("x-max-length", 5000)
                .build();
    }

    // ---------------------------------------------------------------------
    // Bindings direct
    // ---------------------------------------------------------------------

    @Bean
    public Binding bindingEmailDirect() {
        return BindingBuilder.bind(colaEmail()).to(directExchange()).with(RK_EMAIL_SEND);
    }

    @Bean
    public Binding bindingKitchenDirect() {
        return BindingBuilder.bind(colaKitchen()).to(directExchange()).with(RK_KITCHEN_TICKET);
    }

    @Bean
    public Binding bindingInvoiceDirect() {
        return BindingBuilder.bind(colaInvoice()).to(directExchange()).with(RK_INVOICE_GEN);
    }

    // ---------------------------------------------------------------------
    // Bindings topic
    // ---------------------------------------------------------------------

    @Bean
    public Binding bindingEmailTopic() {
        return BindingBuilder.bind(colaEmail()).to(topicExchange()).with(RK_EMAIL_TOPIC);
    }

    @Bean
    public Binding bindingKitchenTopic() {
        return BindingBuilder.bind(colaKitchen()).to(topicExchange()).with(RK_KITCHEN_TOPIC);
    }

    @Bean
    public Binding bindingInvoiceTopic() {
        return BindingBuilder.bind(colaInvoice()).to(topicExchange()).with(RK_INVOICE_TOPIC);
    }

    // ---------------------------------------------------------------------
    // Bindings de las DLQ hacia el Dead Letter Exchange
    // ---------------------------------------------------------------------

    @Bean
    public Binding bindingEmailDlq() {
        return BindingBuilder.bind(dlqEmail()).to(deadLetterExchange()).with(RK_EMAIL_DEAD);
    }

    @Bean
    public Binding bindingKitchenDlq() {
        return BindingBuilder.bind(dlqKitchen()).to(deadLetterExchange()).with(RK_KITCHEN_DEAD);
    }

    @Bean
    public Binding bindingInvoiceDlq() {
        return BindingBuilder.bind(dlqInvoice()).to(deadLetterExchange()).with(RK_INVOICE_DEAD);
    }

    // ---------------------------------------------------------------------
    // Serializacion
    // ---------------------------------------------------------------------

    /**
     * Los mensajes viajan como JSON y no como objetos serializados de Java,
     * para que el cuerpo sea legible en RabbitMQ Management durante la
     * demostracion y para no acoplar productor y consumidor a una misma clase.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ---------------------------------------------------------------------
    // Confirmaciones de publicacion
    // ---------------------------------------------------------------------

    /**
     * Ajusta el RabbitTemplate que autoconfigura Spring Boot para que el
     * productor sepa si el broker realmente acepto cada mensaje.
     *
     * Sin esto, convertAndSend devuelve en cuanto el mensaje sale del proceso y
     * el log diria "publicado" aunque el broker lo hubiera descartado: la
     * confirmacion es lo que distingue "lo envie" de "lo recibieron".
     *
     * Son dos mecanismos distintos y responden preguntas distintas:
     *
     *   ConfirmCallback   el broker acepto el mensaje (ack) o no (nack). Exige
     *                     spring.rabbitmq.publisher-confirm-type=correlated.
     *   ReturnsCallback   el mensaje llego al exchange pero ninguna cola calzo
     *                     con su routing key, asi que volvio. Solo se dispara si
     *                     el mensaje se envia como mandatory, que es lo que se
     *                     activa aqui; sin mandatory, RabbitMQ lo descarta en
     *                     silencio. Es la red de seguridad contra una routing
     *                     key mal escrita.
     */
    @Bean
    public RabbitTemplateCustomizer confirmacionesDePublicacion() {
        return template -> {
            template.setMandatory(true);

            template.setConfirmCallback((correlacion, aceptado, motivo) -> {
                String eventId = correlacion != null ? correlacion.getId() : "desconocido";
                if (aceptado) {
                    log.debug("Broker confirmo el mensaje | eventId: {}", eventId);
                } else {
                    log.error("Broker RECHAZO el mensaje | eventId: {} | motivo: {}", eventId, motivo);
                }
            });

            template.setReturnsCallback(devuelto -> log.error(
                    "Mensaje DEVUELTO sin cola destino | Exchange: {} | Routing Key: {} | {} {}",
                    devuelto.getExchange(), devuelto.getRoutingKey(),
                    devuelto.getReplyCode(), devuelto.getReplyText()));
        };
    }
}
