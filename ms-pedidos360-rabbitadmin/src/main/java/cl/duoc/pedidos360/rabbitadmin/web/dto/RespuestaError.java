package cl.duoc.pedidos360.rabbitadmin.web.dto;

/**
 * Cuerpo de un 400. Nombra el parametro que falta, y no solo el mensaje,
 * para que quien llama sepa que corregir sin leer la documentacion.
 *
 * @param error      descripcion legible del problema
 * @param parametro  parametro que lo provoco
 */
public record RespuestaError(String error, String parametro) {
}
