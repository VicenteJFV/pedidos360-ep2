package cl.duoc.pedidos360.rabbitadmin.config;

import java.util.List;

/**
 * Nombres de la topologia del Caso 0 - Pedidos360, los mismos que declaran
 * ms-pedidos360-orders y ms-pedidos360-notify.
 *
 * Aqui estan solo como nombres, sin un @Bean que los declare. Es deliberado:
 * este microservicio administra la topologia pero no es su dueño. Si declarara
 * las colas al arrancar, bastaria con reiniciarlo para resucitar una cola que
 * alguien acaba de eliminar a proposito, y las pruebas de eliminacion de la
 * guia dejarian de significar algo.
 *
 * Los necesita para las metricas: para informar cuantos mensajes hay pendientes
 * o cuantos quedaron en las DLQ hay que saber a que colas preguntarle.
 */
public final class NombresDelCaso {

    public static final String EXCHANGE_DIRECT = "cmd.direct";
    public static final String EXCHANGE_TOPIC = "cmd.topic";
    public static final String EXCHANGE_DLX = "cmd.dead.dlx";

    public static final String QUEUE_EMAIL = "q.cmd.email";
    public static final String QUEUE_KITCHEN = "q.cmd.kitchen";
    public static final String QUEUE_INVOICE = "q.cmd.invoice";

    public static final String DLQ_EMAIL = "q.cmd.email.dlq";
    public static final String DLQ_KITCHEN = "q.cmd.kitchen.dlq";
    public static final String DLQ_INVOICE = "q.cmd.invoice.dlq";

    /** Las tres colas funcionales del caso, en el orden del flujo de negocio. */
    public static final List<String> COLAS_FUNCIONALES =
            List.of(QUEUE_EMAIL, QUEUE_KITCHEN, QUEUE_INVOICE);

    /** Las tres DLQ, una por cola funcional. */
    public static final List<String> COLAS_MUERTAS =
            List.of(DLQ_EMAIL, DLQ_KITCHEN, DLQ_INVOICE);

    private NombresDelCaso() {
    }
}
