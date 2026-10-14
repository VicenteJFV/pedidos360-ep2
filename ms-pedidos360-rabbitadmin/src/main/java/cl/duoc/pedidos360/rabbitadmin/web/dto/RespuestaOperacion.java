package cl.duoc.pedidos360.rabbitadmin.web.dto;

/**
 * Resultado de una operacion administrativa.
 *
 * @param operacion  que se hizo: crear, eliminar, purgar
 * @param recurso    tipo de recurso: cola, exchange o binding
 * @param nombre     nombre del recurso afectado
 * @param detalle    informacion adicional cuando aporta algo
 */
public record RespuestaOperacion(
        String operacion,
        String recurso,
        String nombre,
        String detalle) {

    public static RespuestaOperacion de(String operacion, String recurso, String nombre) {
        return new RespuestaOperacion(operacion, recurso, nombre, null);
    }
}
