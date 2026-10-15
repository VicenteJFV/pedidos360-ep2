package cl.duoc.pedidos360.notify.consumer;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import cl.duoc.pedidos360.notify.messaging.RabbitMQConfig;
import cl.duoc.pedidos360.notify.trabajo.ErrorPermanenteException;
import cl.duoc.pedidos360.notify.trabajo.ErrorTransitorioException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pruebas de la decision de ACK/NACK, que es el corazon del consumidor.
 *
 * El canal AMQP se simula: lo que importa no es que RabbitMQ reciba la
 * confirmacion -eso se verifico contra el cluster- sino que el codigo llame
 * exactamente una vez a basicAck o a basicNack y nunca a las dos.
 *
 * La politica usa esperas de milisegundos y no de segundos para que la prueba
 * no tarde siete segundos en correr.
 */
class EjecutorDeConsumoTest {

    private static final PoliticaDeReintentos POLITICA_RAPIDA = new PoliticaDeReintentos(
            3, Duration.ofMillis(1), 2, Duration.ofMillis(4));

    private static final long TAG = 42L;

    private Channel canal;
    private EjecutorDeConsumo ejecutor;

    @BeforeEach
    void preparar() {
        canal = mock(Channel.class);
        ejecutor = new EjecutorDeConsumo(POLITICA_RAPIDA);
    }

    private ComandoPedido comando() {
        return new ComandoPedido("evt-1", "PED-1", "EMAIL", 1L, "PED-1", "ACEPTADO",
                "cliente@duoc.cl", "Cliente", "1000.00", "Producto x1", OffsetDateTime.now());
    }

    private void ejecutar(Runnable trabajo) throws IOException {
        ejecutor.ejecutar(RabbitMQConfig.QUEUE_EMAIL, RabbitMQConfig.RK_EMAIL_SEND,
                comando(), canal, TAG, trabajo);
    }

    @Test
    @DisplayName("Procesamiento correcto: un solo ACK y ningun NACK")
    void exitoConfirmaUnaVez() throws IOException {
        ejecutar(() -> { });

        verify(canal, times(1)).basicAck(TAG, false);
        verify(canal, never()).basicNack(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("Error permanente: NACK sin requeue de inmediato, sin reintentar")
    void errorPermanenteRechazaSinReintentar() throws IOException {
        AtomicInteger invocaciones = new AtomicInteger();

        ejecutar(() -> {
            invocaciones.incrementAndGet();
            throw new ErrorPermanenteException("la direccion no existe");
        });

        assertThat(invocaciones.get())
                .as("un error permanente no se reintenta: reintentarlo daria el mismo resultado")
                .isEqualTo(1);
        verify(canal, times(1)).basicNack(TAG, false, false);
        verify(canal, never()).basicAck(TAG, false);
    }

    @Test
    @DisplayName("Error transitorio: reintenta y recien al agotarse manda el mensaje a la DLQ")
    void errorTransitorioReintentaYLuegoRechaza() throws IOException {
        AtomicInteger invocaciones = new AtomicInteger();

        ejecutar(() -> {
            invocaciones.incrementAndGet();
            throw new ErrorTransitorioException("el proveedor no responde");
        });

        assertThat(invocaciones.get())
                .as("intento inicial mas tres reintentos")
                .isEqualTo(4);
        verify(canal, times(1)).basicNack(TAG, false, false);
        verify(canal, never()).basicAck(TAG, false);
    }

    @Test
    @DisplayName("Error transitorio que se arregla en el segundo intento: termina en ACK")
    void errorTransitorioQueSeRecupera() throws IOException {
        AtomicInteger invocaciones = new AtomicInteger();

        ejecutar(() -> {
            if (invocaciones.incrementAndGet() == 1) {
                throw new ErrorTransitorioException("caida momentanea");
            }
        });

        assertThat(invocaciones.get()).isEqualTo(2);
        verify(canal, times(1)).basicAck(TAG, false);
        verify(canal, never()).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Una excepcion inesperada no deja el mensaje sin confirmar: se rechaza")
    void errorInesperadoTambienRechaza() throws IOException {
        ejecutar(() -> {
            throw new IllegalStateException("defecto del codigo");
        });

        // Si no se rechazara, el mensaje quedaria en Unacked y con prefetch=1
        // detendria la cola completa. Es exactamente la falla que se busca evitar.
        verify(canal, times(1)).basicNack(TAG, false, false);
        verify(canal, never()).basicAck(TAG, false);
    }

    @Test
    @DisplayName("El rechazo nombra la DLQ que corresponde a cada cola")
    void cadaColaTieneSuDlq() {
        assertThat(RabbitMQConfig.dlqDe(RabbitMQConfig.QUEUE_EMAIL))
                .isEqualTo(RabbitMQConfig.DLQ_EMAIL);
        assertThat(RabbitMQConfig.dlqDe(RabbitMQConfig.QUEUE_KITCHEN))
                .isEqualTo(RabbitMQConfig.DLQ_KITCHEN);
        assertThat(RabbitMQConfig.dlqDe(RabbitMQConfig.QUEUE_INVOICE))
                .isEqualTo(RabbitMQConfig.DLQ_INVOICE);
    }
}
