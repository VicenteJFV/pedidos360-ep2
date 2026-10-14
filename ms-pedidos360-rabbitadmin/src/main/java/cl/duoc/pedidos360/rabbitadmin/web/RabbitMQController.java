package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.service.RabbitResourceManager;
import cl.duoc.pedidos360.rabbitadmin.service.TipoExchange;
import cl.duoc.pedidos360.rabbitadmin.web.dto.InfoCola;
import cl.duoc.pedidos360.rabbitadmin.web.dto.MetricasBroker;
import cl.duoc.pedidos360.rabbitadmin.web.dto.RespuestaError;
import cl.duoc.pedidos360.rabbitadmin.web.dto.RespuestaOperacion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * API de administracion de RabbitMQ.
 *
 *   POST   /api/rabbitmq/queues?queueName=
 *   GET    /api/rabbitmq/queues/{queueName}
 *   DELETE /api/rabbitmq/queues/{queueName}
 *   POST   /api/rabbitmq/queues/{queueName}/purge
 *   POST   /api/rabbitmq/exchanges?exchangeName=&type=
 *   DELETE /api/rabbitmq/exchanges/{exchangeName}
 *   POST   /api/rabbitmq/bindings?queueName=&exchangeName=&routingKey=
 *   DELETE /api/rabbitmq/bindings?queueName=&exchangeName=&routingKey=
 *   GET    /api/rabbitmq/metricas
 *
 * El controlador valida y traduce a HTTP; no toca el broker. Toda la validacion
 * ocurre ANTES de llamar al servicio, que es el punto de la Etapa 11 de la
 * guia: un nombre vacio se rechaza con 400 sin abrir una conexion, porque
 * declarar una cola con nombre vacio en RabbitMQ no es un error sino una cola
 * con nombre generado, y eso no es lo que nadie quiso pedir.
 */
@RestController
@RequestMapping("/api/rabbitmq")
public class RabbitMQController {

    private final RabbitResourceManager administrador;

    public RabbitMQController(RabbitResourceManager administrador) {
        this.administrador = administrador;
    }

    // ---------------------------------------------------------------------
    // Colas
    // ---------------------------------------------------------------------

    @PostMapping("/queues")
    public ResponseEntity<?> crearCola(@RequestParam(required = false) String queueName) {
        if (vacio(queueName)) {
            return faltaParametro("queueName");
        }

        administrador.crearCola(queueName.trim());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RespuestaOperacion.de("crear", "cola", queueName.trim()));
    }

    @GetMapping("/queues/{queueName}")
    public ResponseEntity<?> consultarCola(@PathVariable String queueName) {
        if (vacio(queueName)) {
            return faltaParametro("queueName");
        }

        InfoCola info = administrador.consultarCola(queueName.trim());
        return ResponseEntity.ok(info);
    }

    @DeleteMapping("/queues/{queueName}")
    public ResponseEntity<?> eliminarCola(@PathVariable String queueName) {
        if (vacio(queueName)) {
            return faltaParametro("queueName");
        }

        administrador.eliminarCola(queueName.trim());

        // Sin detalle sobre si existia: el borrado es idempotente y el broker no
        // informa de forma fiable si el recurso estaba. Para comprobarlo, el GET
        // de la cola responde 404 despues de eliminarla.
        return ResponseEntity.ok(RespuestaOperacion.de("eliminar", "cola", queueName.trim()));
    }

    @PostMapping("/queues/{queueName}/purge")
    public ResponseEntity<?> purgarCola(@PathVariable String queueName) {
        if (vacio(queueName)) {
            return faltaParametro("queueName");
        }

        int purgados = administrador.purgarCola(queueName.trim());

        return ResponseEntity.ok(new RespuestaOperacion("purgar", "cola", queueName.trim(),
                purgados + " mensajes descartados"));
    }

    // ---------------------------------------------------------------------
    // Exchanges
    // ---------------------------------------------------------------------

    @PostMapping("/exchanges")
    public ResponseEntity<?> crearExchange(@RequestParam(required = false) String exchangeName,
                                           @RequestParam(required = false) String type) {
        if (vacio(exchangeName)) {
            return faltaParametro("exchangeName");
        }
        if (vacio(type)) {
            return faltaParametro("type");
        }

        Optional<TipoExchange> tipo = TipoExchange.desde(type);
        if (tipo.isEmpty()) {
            return ResponseEntity.badRequest().body(new RespuestaError(
                    "type debe ser uno de: " + TipoExchange.validos(), "type"));
        }

        administrador.crearExchange(exchangeName.trim(), tipo.get());

        return ResponseEntity.status(HttpStatus.CREATED).body(new RespuestaOperacion(
                "crear", "exchange", exchangeName.trim(), "tipo " + tipo.get().name().toLowerCase()));
    }

    @DeleteMapping("/exchanges/{exchangeName}")
    public ResponseEntity<?> eliminarExchange(@PathVariable String exchangeName) {
        if (vacio(exchangeName)) {
            return faltaParametro("exchangeName");
        }

        administrador.eliminarExchange(exchangeName.trim());

        return ResponseEntity.ok(
                RespuestaOperacion.de("eliminar", "exchange", exchangeName.trim()));
    }

    // ---------------------------------------------------------------------
    // Bindings
    // ---------------------------------------------------------------------

    @PostMapping("/bindings")
    public ResponseEntity<?> crearBinding(@RequestParam(required = false) String queueName,
                                          @RequestParam(required = false) String exchangeName,
                                          @RequestParam(required = false) String routingKey) {
        ResponseEntity<?> error = validarBinding(queueName, exchangeName, routingKey);
        if (error != null) {
            return error;
        }

        administrador.crearBinding(queueName.trim(), exchangeName.trim(), routingKey.trim());

        return ResponseEntity.status(HttpStatus.CREATED).body(new RespuestaOperacion(
                "crear", "binding", descripcion(queueName, exchangeName, routingKey), null));
    }

    @DeleteMapping("/bindings")
    public ResponseEntity<?> eliminarBinding(@RequestParam(required = false) String queueName,
                                             @RequestParam(required = false) String exchangeName,
                                             @RequestParam(required = false) String routingKey) {
        ResponseEntity<?> error = validarBinding(queueName, exchangeName, routingKey);
        if (error != null) {
            return error;
        }

        administrador.eliminarBinding(queueName.trim(), exchangeName.trim(), routingKey.trim());

        return ResponseEntity.ok(new RespuestaOperacion(
                "eliminar", "binding", descripcion(queueName, exchangeName, routingKey), null));
    }

    /**
     * Los tres parametros son obligatorios porque un binding ES la combinacion de
     * los tres: sin uno de ellos no hay nada que crear ni nada que eliminar.
     *
     * @return null si todo esta en orden, o la respuesta 400 que corresponde
     */
    private ResponseEntity<?> validarBinding(String cola, String exchange, String routingKey) {
        if (vacio(cola)) {
            return faltaParametro("queueName");
        }
        if (vacio(exchange)) {
            return faltaParametro("exchangeName");
        }
        if (vacio(routingKey)) {
            return faltaParametro("routingKey");
        }
        return null;
    }

    private String descripcion(String cola, String exchange, String routingKey) {
        return exchange.trim() + " -> " + cola.trim() + " (" + routingKey.trim() + ")";
    }

    // ---------------------------------------------------------------------
    // Metricas
    // ---------------------------------------------------------------------

    @GetMapping("/metricas")
    public MetricasBroker metricas() {
        return administrador.metricas();
    }

    // ---------------------------------------------------------------------
    // Validacion
    // ---------------------------------------------------------------------

    private boolean vacio(String valor) {
        return !StringUtils.hasText(valor);
    }

    private ResponseEntity<RespuestaError> faltaParametro(String parametro) {
        return ResponseEntity.badRequest()
                .body(new RespuestaError(parametro + " es obligatorio", parametro));
    }
}
