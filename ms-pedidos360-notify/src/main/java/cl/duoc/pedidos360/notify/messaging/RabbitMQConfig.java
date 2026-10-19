package cl.duoc.pedidos360.notify.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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
 * Esta misma configuracion se declara tambien en ms-pedidos360-orders, que es
 * el productor. Esta repetida a proposito: cada microservicio debe poder
 * arrancar solo, sin esperar a que el otro haya creado las colas. El precio es
 * que las dos declaraciones deben coincidir exactamente -durabilidad y
 * argumentos- o RabbitMQ rechaza la segunda con
 * PRECONDITION_FAILED - inequivalent arg.
 */
@Configuration
public class RabbitMQConfig {

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

    /**
     * DLQ que corresponde a una cola funcional. El consumidor la necesita solo
     * para poder nombrarla en el log cuando rechaza un mensaje; el enrutamiento
     * real lo hace RabbitMQ con los argumentos de la cola, no este metodo.
     *
     * Se resuelve con un switch y no concatenando ".dlq" para que los nombres
     * sigan viniendo de las constantes: si manana una DLQ se llamara distinto,
     * basta cambiar la constante.
     */
    public static String dlqDe(String colaFuncional) {
        return switch (colaFuncional) {
            case QUEUE_EMAIL -> DLQ_EMAIL;
            case QUEUE_KITCHEN -> DLQ_KITCHEN;
            case QUEUE_INVOICE -> DLQ_INVOICE;
            default -> throw new IllegalArgumentException(
                    "No hay DLQ declarada para la cola " + colaFuncional);
        };
    }

    // ---------------------------------------------------------------------
    // Routing keys
    // ---------------------------------------------------------------------

    /** Bindings direct: la clave debe coincidir caracter por caracter. */
    public static final String RK_EMAIL_SEND = "email.send";
    public static final String RK_KITCHEN_TICKET = "kitchen.ticket";
    public static final String RK_INVOICE_GEN = "invoice.gen";

    /**
     * Bindings topic. Las routing keys son palabras separadas por puntos; el
     * comodin * reemplaza EXACTAMENTE UNA palabra y el # reemplaza cero o mas.
     *
     *   email.*    calza con email.send         (una palabra despues de email)
     *              NO calza con email.send.high (son dos)
     *   kitchen.#  calza con kitchen.ticket, kitchen.ticket.new y hasta kitchen
     *
     * Por eso el flujo que demuestra el topic publica kitchen.ticket.new: es una
     * clave real del negocio que solo llega a q.cmd.kitchen por el patron, no
     * por coincidencia exacta.
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
     *
     * El productor manda una cabecera __TypeId__ con el nombre de SU clase,
     * cl.duoc.pedidos360.orders.messaging.ComandoPedido, que en este
     * microservicio no existe. Con TypePrecedence.INFERRED se ignora esa
     * cabecera y manda el tipo del parametro del @RabbitListener, que es lo que
     * se quiere: el contrato entre ambos es el JSON, no el nombre del paquete.
     * Es el valor por omision, pero se deja escrito porque de el depende que el
     * consumidor no se caiga con ClassNotFoundException.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter convertidor = new Jackson2JsonMessageConverter();

        DefaultJackson2JavaTypeMapper mapeadorDeTipos = new DefaultJackson2JavaTypeMapper();
        mapeadorDeTipos.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        convertidor.setJavaTypeMapper(mapeadorDeTipos);

        return convertidor;
    }
}
