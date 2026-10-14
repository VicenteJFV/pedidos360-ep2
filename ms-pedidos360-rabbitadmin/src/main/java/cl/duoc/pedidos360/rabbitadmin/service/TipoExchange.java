package cl.duoc.pedidos360.rabbitadmin.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tipos de exchange que la API acepta al crear uno.
 *
 * Existe como enum y no como String para que el 400 por tipo invalido salga de
 * la validacion y no de una excepcion del broker a medio camino: un
 * "type=directo" mal escrito se rechaza antes de abrir el canal.
 */
public enum TipoExchange {

    DIRECT,
    TOPIC,
    FANOUT,
    HEADERS;

    public static Optional<TipoExchange> desde(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(tipo -> tipo.name().equalsIgnoreCase(valor.trim()))
                .findFirst();
    }

    /** Lista legible para el mensaje de error. */
    public static String validos() {
        return Arrays.stream(values())
                .map(tipo -> tipo.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
