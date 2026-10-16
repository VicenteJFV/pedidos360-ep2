package pedidos360.bff.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pedidos360.bff.config.FiltroContentLength;
import pedidos360.bff.security.SecurityConfig;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del reenvio de /api/rabbitmq hacia ms-pedidos360-rabbitadmin.
 *
 * rabbitadmin se reemplaza por un servidor simulado: lo que se verifica aqui es
 * lo que hace el BFF -quien puede pasar, que URL llama y que devuelve-, no la
 * API administradora, que tiene sus propias pruebas.
 */
@WebMvcTest(RabbitAdminController.class)
@Import({SecurityConfig.class, FiltroContentLength.class})
class RabbitAdminControllerTest {

    private static final String RABBITADMIN = "http://127.0.0.1:8084";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitAdminController controlador;

    private MockRestServiceServer rabbitAdmin;

    @BeforeEach
    void simularRabbitAdmin() {
        // @PreAuthorize hace que el bean sea un proxy: el campo leido a traves del
        // proxy es nulo. Se toma el objeto real que hay detras.
        RabbitAdminController real = AopTestUtils.getTargetObject(controlador);
        rabbitAdmin = MockRestServiceServer.bindTo(real.restTemplate).build();
    }

    private static SimpleGrantedAuthority rol(String nombre) {
        return new SimpleGrantedAuthority("ROLE_" + nombre);
    }

    @Test
    @DisplayName("Sin token: 401 y rabbitadmin no recibe nada")
    void sinToken() throws Exception {
        mockMvc.perform(get("/api/rabbitmq/metricas"))
                .andExpect(status().isUnauthorized());

        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("Cliente: 403. La administracion de RabbitMQ es solo para Admin")
    void clienteNoPuede() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/queues").param("queueName", "q.test.pedidos360")
                        .with(jwt().authorities(rol("Cliente"))))
                .andExpect(status().isForbidden());

        // Ninguna peticion llego a rabbitadmin: verify falla si hubo alguna.
        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("Admin crea una cola: se reenvia metodo, ruta y query string, y vuelve el 201")
    void adminCreaCola() throws Exception {
        rabbitAdmin.expect(requestTo(RABBITADMIN + "/api/rabbitmq/queues?queueName=q.test.pedidos360"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"operacion\":\"crear\",\"recurso\":\"cola\",\"nombre\":\"q.test.pedidos360\"}"));

        // El parametro va en la URL, como lo manda Postman: el BFF reenvia la query
        // string real. .param() de MockMvc no la llena, aunque el servidor si lo haria.
        mockMvc.perform(post("/api/rabbitmq/queues?queueName=q.test.pedidos360")
                        .with(jwt().authorities(rol("Admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("q.test.pedidos360"));

        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("El 400 de rabbitadmin llega intacto, con su cuerpo y su Content-Length")
    void elCuatrocientosLlegaConCuerpo() throws Exception {
        String cuerpo = "{\"error\":\"queueName es obligatorio\",\"parametro\":\"queueName\"}";
        rabbitAdmin.expect(requestTo(RABBITADMIN + "/api/rabbitmq/queues?queueName="))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(cuerpo));

        // Es la prueba 3 de la guia empirica pasando por el BFF.
        MvcResult resultado = mockMvc.perform(post("/api/rabbitmq/queues?queueName=")
                        .with(jwt().authorities(rol("Admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(cuerpo))
                .andReturn();

        // Tomcat cierra la conexion tras un 400: sin Content-Length, el API Gateway
        // podria quedarse con el codigo pero sin el cuerpo.
        String longitud = resultado.getResponse().getHeader("Content-Length");
        assertThat(longitud).isEqualTo(String.valueOf(resultado.getResponse().getContentAsByteArray().length));
        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("La query string se reenvia sin volver a codificarla")
    void noDobleCodificacion() throws Exception {
        // Si el BFF pasara la URL como String, RestTemplate codificaria el % otra
        // vez y rabbitadmin recibiria %2520 en lugar de dos espacios.
        rabbitAdmin.expect(requestTo(URI.create(RABBITADMIN + "/api/rabbitmq/queues?queueName=%20%20")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"queueName es obligatorio\",\"parametro\":\"queueName\"}"));

        mockMvc.perform(post(URI.create("/api/rabbitmq/queues?queueName=%20%20"))
                        .with(jwt().authorities(rol("Admin"))))
                .andExpect(status().isBadRequest());

        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("Admin elimina una cola: el DELETE se reenvia con su ruta")
    void adminEliminaCola() throws Exception {
        rabbitAdmin.expect(requestTo(RABBITADMIN + "/api/rabbitmq/queues/q.test.pedidos360"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"operacion\":\"eliminar\"}", MediaType.APPLICATION_JSON));

        mockMvc.perform(delete("/api/rabbitmq/queues/q.test.pedidos360")
                        .with(jwt().authorities(rol("Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operacion").value("eliminar"));

        rabbitAdmin.verify();
    }

    @Test
    @DisplayName("Las metricas se reenvian con GET")
    void metricas() throws Exception {
        rabbitAdmin.expect(requestTo(RABBITADMIN + "/api/rabbitmq/metricas"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"colasActivas\":6}", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/rabbitmq/metricas").with(jwt().authorities(rol("Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colasActivas").value(6));

        rabbitAdmin.verify();
    }
}
