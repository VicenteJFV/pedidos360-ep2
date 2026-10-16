package pedidos360.bff.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Reenvia la API del microservicio administrador de RabbitMQ (EP2).
 *
 * ms-pedidos360-rabbitadmin escucha solo dentro de la EC2 y no tiene
 * autenticacion propia: puede crear y eliminar colas, exchanges y bindings. Por
 * eso no se publica directo, sino que entra por el mismo camino que el resto de
 * la API -API Gateway con JWT, y el BFF con autorizacion por rol- y solo para
 * Admin. Es el mismo modelo de EP1: el Gateway valida el token, el BFF decide
 * que puede hacer cada rol y los microservicios no ven el token.
 *
 * Reenvia todo lo que cuelga de /api/rabbitmq tal cual -metodo, ruta, query
 * string y cuerpo- en lugar de declarar un metodo por endpoint, porque el BFF no
 * agrega logica a estas operaciones: la validacion de parametros es de
 * rabbitadmin, y su 400 llega al cliente sin cambios.
 */
@RestController
@RequestMapping("/api/rabbitmq")
@PreAuthorize("hasAuthority('ROLE_Admin')")
public class RabbitAdminController {

    private final String rabbitAdminBaseUrl;

    /** Package-private para poder reemplazarlo por un servidor simulado en las pruebas. */
    final RestTemplate restTemplate = new RestTemplate();

    public RabbitAdminController(
            @Value("${microservicios.rabbitadmin.base-url:http://127.0.0.1:8084}") String rabbitAdminBaseUrl) {
        this.rabbitAdminBaseUrl = rabbitAdminBaseUrl;
    }

    @RequestMapping({"", "/**"})
    public ResponseEntity<String> reenviar(HttpServletRequest peticion,
                                           @RequestBody(required = false) String cuerpo) {

        String ruta = peticion.getRequestURI().substring(peticion.getContextPath().length());
        String consulta = peticion.getQueryString();

        // URI ya codificada, tal como llego. Si se pasara como String, RestTemplate
        // la volveria a codificar y un queueName=%20 llegaria como %2520.
        URI destino = URI.create(rabbitAdminBaseUrl + ruta + (consulta != null ? "?" + consulta : ""));

        HttpHeaders cabeceras = new HttpHeaders();
        if (cuerpo != null) {
            cabeceras.setContentType(MediaType.APPLICATION_JSON);
        }

        try {
            ResponseEntity<String> respuesta = restTemplate.exchange(destino,
                    HttpMethod.valueOf(peticion.getMethod()),
                    new HttpEntity<>(cuerpo, cabeceras), String.class);
            return ResponseEntity.status(respuesta.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(respuesta.getBody());
        } catch (HttpStatusCodeException e) {
            // Solo el estado y el cuerpo. No se copian las cabeceras de la respuesta
            // de rabbitadmin: Transfer-Encoding y Connection son hop-by-hop, y
            // reenviarlas rompia la respuesta en EP1 (500 con cuerpo vacio).
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        }
    }
}
