package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.service.RecursoNoEncontradoException;
import cl.duoc.pedidos360.rabbitadmin.web.dto.RespuestaError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las fallas a respuestas HTTP, para que la API nunca devuelva un 500
 * con una traza de Java en el cuerpo.
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<RespuestaError> noEncontrado(RecursoNoEncontradoException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new RespuestaError(e.getMessage(), null));
    }

    /**
     * Un parametro ausente tambien es un 400. Se maneja aqui porque Spring lanza
     * esta excepcion antes de entrar al metodo del controlador cuando el
     * parametro es obligatorio; los @RequestParam de esta API se declaran con
     * required=false precisamente para validarlos a mano y poder nombrarlos, pero
     * si algun dia alguien agrega uno obligatorio, la respuesta seguira siendo
     * coherente.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<RespuestaError> parametroAusente(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new RespuestaError(e.getParameterName() + " es obligatorio", e.getParameterName()));
    }

    /**
     * Las fallas del broker se devuelven como 409 y no como 500: no son un
     * defecto del servicio sino un conflicto con el estado real de RabbitMQ.
     * El caso tipico es PRECONDITION_FAILED al intentar redeclarar una cola
     * existente con argumentos distintos, que es exactamente la proteccion que
     * impide que esta API pise la topologia del caso.
     */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<RespuestaError> fallaDelBroker(AmqpException e) {
        log.error("El broker rechazo la operacion", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new RespuestaError("RabbitMQ rechazo la operacion: " + mensajeRaiz(e), null));
    }

    /** El mensaje util suele estar en la causa mas profunda, no en la envoltura. */
    private String mensajeRaiz(Throwable e) {
        Throwable raiz = e;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        return raiz.getMessage() != null ? raiz.getMessage() : e.toString();
    }
}
