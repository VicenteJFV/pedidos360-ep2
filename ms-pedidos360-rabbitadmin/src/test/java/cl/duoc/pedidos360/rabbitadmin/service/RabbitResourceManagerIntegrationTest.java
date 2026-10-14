package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.config.NombresDelCaso;
import cl.duoc.pedidos360.rabbitadmin.web.dto.InfoCola;
import cl.duoc.pedidos360.rabbitadmin.web.dto.MetricasBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueba de integracion contra el cluster real.
 *
 * Las pruebas de RabbitMQControllerTest simulan el broker, asi que verifican la
 * validacion pero no que las operaciones funcionen de verdad. Esta clase declara
 * y elimina recursos reales.
 *
 * Si el cluster no esta levantado, las pruebas se SALTAN en vez de fallar: una
 * prueba de integracion que falla porque falta la infraestructura no informa
 * nada util y vuelve inutilizable el build de cualquiera que no tenga Docker
 * corriendo. Para ejecutarlas:
 *
 *     cd rabbitmq-cluster
 *     .\levantar-cluster.ps1
 *
 * Los recursos que crea llevan el prefijo prueba.it. y se eliminan al terminar,
 * para no dejar basura en el broker ni confundir las metricas de la
 * demostracion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RabbitResourceManagerIntegrationTest {

    private static final String COLA = "q.prueba.it";
    private static final String EXCHANGE = "ex.prueba.it";
    private static final String ROUTING_KEY = "prueba.it.rk";

    @Autowired
    private RabbitResourceManager administrador;

    @Autowired
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void exigirCluster() {
        assumeTrue(clusterDisponible(), "El cluster RabbitMQ no esta disponible");
        limpiar();
    }

    @AfterEach
    void limpiarDespues() {
        if (clusterDisponible()) {
            limpiar();
        }
    }

    private boolean clusterDisponible() {
        try (Connection conexion = connectionFactory.createConnection()) {
            return conexion.isOpen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void limpiar() {
        administrador.eliminarCola(COLA);
        administrador.eliminarExchange(EXCHANGE);
    }

    @Test
    @DisplayName("Crear una cola la deja consultable en el broker")
    void crearYConsultarCola() {
        administrador.crearCola(COLA);

        InfoCola info = administrador.consultarCola(COLA);
        assertThat(info.nombre()).isEqualTo(COLA);
        assertThat(info.mensajes()).isZero();
        assertThat(info.consumidores()).isZero();
    }

    @Test
    @DisplayName("Consultar una cola que no existe falla con RecursoNoEncontrado")
    void consultarColaInexistente() {
        assertThatThrownBy(() -> administrador.consultarCola("q.no.existe.it"))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Declarar dos veces la misma cola no falla: declarar es idempotente")
    void declararDosVecesEsIdempotente() {
        administrador.crearCola(COLA);
        administrador.crearCola(COLA);

        assertThat(administrador.consultarCola(COLA).nombre()).isEqualTo(COLA);
    }

    @Test
    @DisplayName("Eliminar deja la cola inexistente, y repetirlo no falla")
    void eliminarEsIdempotente() {
        administrador.crearCola(COLA);

        administrador.eliminarCola(COLA);
        administrador.eliminarCola(COLA);

        assertThatThrownBy(() -> administrador.consultarCola(COLA))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Un exchange se crea con el tipo pedido y se elimina")
    void crearYEliminarExchange() {
        administrador.crearExchange(EXCHANGE, TipoExchange.TOPIC);

        // Si la declaracion o el borrado fallaran, el broker cerraria el canal y
        // la excepcion llegaria hasta aqui. AMQP no permite consultar un exchange,
        // asi que no hay un assert mas fuerte que este.
        administrador.eliminarExchange(EXCHANGE);
        administrador.eliminarExchange(EXCHANGE);
    }

    @Test
    @DisplayName("Un binding se crea y se elimina reconstruyendo la misma combinacion")
    void crearYEliminarBinding() {
        administrador.crearCola(COLA);
        administrador.crearExchange(EXCHANGE, TipoExchange.DIRECT);

        administrador.crearBinding(COLA, EXCHANGE, ROUTING_KEY);
        administrador.eliminarBinding(COLA, EXCHANGE, ROUTING_KEY);

        // No hay forma de consultar bindings por AMQP: si alguna de las dos
        // operaciones hubiera fallado, el broker habria cerrado el canal con
        // NOT_FOUND y la excepcion habria llegado hasta aqui.
        assertThat(administrador.consultarCola(COLA).nombre()).isEqualTo(COLA);
    }

    @Test
    @DisplayName("Purgar una cola devuelve cuantos mensajes se descartaron")
    void purgarColaVacia() {
        administrador.crearCola(COLA);

        assertThat(administrador.purgarCola(COLA)).isZero();
    }

    @Test
    @DisplayName("Purgar una cola inexistente falla con RecursoNoEncontrado")
    void purgarColaInexistente() {
        assertThatThrownBy(() -> administrador.purgarCola("q.no.existe.it"))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Las metricas encuentran las seis colas del caso")
    void metricasDelCaso() {
        MetricasBroker metricas = administrador.metricas();

        assertThat(metricas.colasEsperadas()).isEqualTo(6);

        // La topologia la declaran orders y notify, no este microservicio. Si
        // ninguno de los dos arranco todavia, las colas no existen y no hay nada
        // que medir: la prueba se salta en vez de inventar una falla.
        assumeTrue(metricas.colasActivas() == 6,
                "La topologia del caso no esta desplegada: arranca orders o notify");

        assertThat(metricas.detalleFuncionales())
                .extracting(InfoCola::nombre)
                .containsExactly(NombresDelCaso.QUEUE_EMAIL,
                        NombresDelCaso.QUEUE_KITCHEN,
                        NombresDelCaso.QUEUE_INVOICE);
        assertThat(metricas.detalleMuertas())
                .extracting(InfoCola::nombre)
                .containsExactly(NombresDelCaso.DLQ_EMAIL,
                        NombresDelCaso.DLQ_KITCHEN,
                        NombresDelCaso.DLQ_INVOICE);
        assertThat(metricas.tasaDlq()).isBetween(0.0, 1.0);
    }
}
