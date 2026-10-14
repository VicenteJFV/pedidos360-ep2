package cl.duoc.pedidos360.notify.trabajo;

import cl.duoc.pedidos360.notify.messaging.ComandoPedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El disparador es lo que permite provocar el error controlado de la prueba
 * empirica sin tocar codigo ni apagar contenedores durante la presentacion:
 * basta crear el pedido con un correo determinado.
 */
class DisparadorDeFallasTest {

    private final DisparadorDeFallas disparador = new DisparadorDeFallas();

    private ComandoPedido conCorreo(String correo) {
        return new ComandoPedido("evt-1", "PED-1", "EMAIL", 1L, "PED-1", "ACEPTADO",
                correo, "Cliente", "1000.00", "Producto x1", OffsetDateTime.now());
    }

    @Test
    @DisplayName("Un correo normal no provoca ninguna falla")
    void correoNormalPasa() {
        assertThatCode(() -> disparador.evaluar(conCorreo("cliente@duoc.cl"), "Envio"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("error-permanente provoca una falla que no se debe reintentar")
    void disparaErrorPermanente() {
        assertThatThrownBy(() -> disparador.evaluar(
                conCorreo("error-permanente@pedidos360.cl"), "Envio"))
                .isInstanceOf(ErrorPermanenteException.class)
                .hasMessageContaining("no existe");
    }

    @Test
    @DisplayName("error-transitorio provoca una falla que si se debe reintentar")
    void disparaErrorTransitorio() {
        assertThatThrownBy(() -> disparador.evaluar(
                conCorreo("error-transitorio@pedidos360.cl"), "Envio"))
                .isInstanceOf(ErrorTransitorioException.class)
                .hasMessageContaining("no responde");
    }

    @Test
    @DisplayName("El disparador no distingue mayusculas")
    void ignoraMayusculas() {
        assertThatThrownBy(() -> disparador.evaluar(
                conCorreo("ERROR-PERMANENTE@PEDIDOS360.CL"), "Envio"))
                .isInstanceOf(ErrorPermanenteException.class);
    }

    @Test
    @DisplayName("Un mensaje sin correo es un error permanente: no hay a quien notificar")
    void mensajeSinCorreo() {
        assertThatThrownBy(() -> disparador.evaluar(conCorreo(null), "Envio"))
                .isInstanceOf(ErrorPermanenteException.class)
                .hasMessageContaining("clienteEmail");

        assertThatThrownBy(() -> disparador.evaluar(conCorreo("   "), "Envio"))
                .isInstanceOf(ErrorPermanenteException.class);
    }

    @Test
    @DisplayName("Un mensaje sin codigo de pedido tampoco se puede procesar")
    void mensajeSinCodigo() {
        ComandoPedido sinCodigo = new ComandoPedido("evt-1", "PED-1", "EMAIL", 1L, null,
                "ACEPTADO", "cliente@duoc.cl", "Cliente", "1000.00", "x1", OffsetDateTime.now());

        assertThatThrownBy(() -> disparador.evaluar(sinCodigo, "Envio"))
                .isInstanceOf(ErrorPermanenteException.class)
                .hasMessageContaining("codigoPedido");
    }
}
