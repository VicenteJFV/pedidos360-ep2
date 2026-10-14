package cl.duoc.pedidos360.notify.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Parametros de reintento del consumidor, con los mismos valores que usan las
 * guias de la asignatura: 3 reintentos esperando 1s, 2s y 4s.
 *
 * @param maximo         reintentos despues del primer intento
 * @param esperaInicial  espera antes del primer reintento
 * @param multiplicador  factor por el que crece la espera en cada reintento
 * @param esperaMaxima   techo de la espera, para que el backoff no se dispare
 */
@ConfigurationProperties(prefix = "notify.reintentos")
public record PoliticaDeReintentos(
        @DefaultValue("3") int maximo,
        @DefaultValue("1s") Duration esperaInicial,
        @DefaultValue("2") double multiplicador,
        @DefaultValue("4s") Duration esperaMaxima) {

    public PoliticaDeReintentos {
        if (maximo < 0) {
            throw new IllegalArgumentException("notify.reintentos.maximo no puede ser negativo");
        }
        if (multiplicador < 1) {
            throw new IllegalArgumentException(
                    "notify.reintentos.multiplicador menor que 1 acortaria la espera");
        }
    }

    /** Intentos totales: el primero mas los reintentos. */
    public int intentosTotales() {
        return maximo + 1;
    }

    /**
     * Espera antes del reintento que sigue al intento indicado, con backoff
     * exponencial: 1s, 2s, 4s, 4s... El techo importa porque sin el la espera se
     * duplica indefinidamente y un mensaje podria quedar horas en el consumidor.
     */
    public Duration esperaTrasIntento(int intento) {
        double milis = esperaInicial.toMillis() * Math.pow(multiplicador, intento - 1.0);
        return Duration.ofMillis(Math.min((long) milis, esperaMaxima.toMillis()));
    }
}
