package cl.duoc.pedidos360.rabbitadmin.web.dto;

import java.util.List;

/**
 * Metricas que la guia de EP2 pide mostrar y explicar.
 *
 * @param colasActivas         cuantas de las colas del caso existen en el
 *                             broker. Comprueba que la topologia esta desplegada
 * @param colasEsperadas       cuantas deberia haber: tres funcionales y tres DLQ
 * @param mensajesPendientes   total de mensajes esperando en las colas
 *                             funcionales. Si crece sin bajar, el consumidor no
 *                             da abasto o esta caido
 * @param consumidores         consumidores conectados a las colas funcionales.
 *                             Verifica que los listeners estan activos
 * @param mensajesEnDlq        total de mensajes en las tres DLQ. Cada uno es un
 *                             mensaje que no se pudo procesar y que alguien
 *                             tiene que revisar
 * @param tasaDlq              proporcion de mensajes muertos sobre el total.
 *                             Una tasa que sube delata un problema recurrente,
 *                             no un incidente aislado
 * @param detalleFuncionales   estado de cada cola funcional
 * @param detalleMuertas       estado de cada DLQ
 */
public record MetricasBroker(
        int colasActivas,
        int colasEsperadas,
        int mensajesPendientes,
        int consumidores,
        int mensajesEnDlq,
        double tasaDlq,
        List<InfoCola> detalleFuncionales,
        List<InfoCola> detalleMuertas) {
}
