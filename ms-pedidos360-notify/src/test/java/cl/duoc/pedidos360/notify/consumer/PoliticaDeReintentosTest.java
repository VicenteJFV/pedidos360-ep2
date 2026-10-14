package cl.duoc.pedidos360.notify.consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoliticaDeReintentosTest {

    private static final PoliticaDeReintentos POLITICA = new PoliticaDeReintentos(
            3, Duration.ofSeconds(1), 2, Duration.ofSeconds(4));

    @Test
    @DisplayName("El backoff es 1s, 2s y 4s, igual que en las guias")
    void backoffExponencial() {
        assertThat(POLITICA.esperaTrasIntento(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(POLITICA.esperaTrasIntento(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(POLITICA.esperaTrasIntento(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("La espera maxima pone techo: sin el, se duplicaria sin limite")
    void laEsperaTieneTecho() {
        assertThat(POLITICA.esperaTrasIntento(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(POLITICA.esperaTrasIntento(10)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("Los intentos totales incluyen el primero, que no es un reintento")
    void intentosTotales() {
        assertThat(POLITICA.intentosTotales()).isEqualTo(4);
        assertThat(new PoliticaDeReintentos(0, Duration.ofSeconds(1), 2, Duration.ofSeconds(4))
                .intentosTotales()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una configuracion absurda falla al arrancar, no en produccion")
    void configuracionInvalida() {
        assertThatThrownBy(() -> new PoliticaDeReintentos(-1, Duration.ofSeconds(1), 2, Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximo");

        assertThatThrownBy(() -> new PoliticaDeReintentos(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplicador");
    }
}
