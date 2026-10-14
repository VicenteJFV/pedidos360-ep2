package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.config.NombresDelCaso;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitResourceManager;
import cl.duoc.pedidos360.rabbitadmin.service.RecursoNoEncontradoException;
import cl.duoc.pedidos360.rabbitadmin.service.TipoExchange;
import cl.duoc.pedidos360.rabbitadmin.web.dto.InfoCola;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de la capa HTTP del microservicio administrador.
 *
 * El servicio que habla con el broker esta simulado: aqui se verifica lo que la
 * guia de EP2 pide verificar de esta capa, que es la validacion de parametros y
 * el codigo de respuesta. Lo importante no es solo que devuelva 400, sino que no
 * llegue a tocar el broker cuando los parametros estan incompletos.
 */
@WebMvcTest(RabbitMQController.class)
class RabbitMQControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RabbitResourceManager administrador;

    // -----------------------------------------------------------------------
    // Creacion valida
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Crear una cola con nombre valido devuelve 201")
    void crearColaValida() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/queues").param("queueName", "q.prueba.ep2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operacion").value("crear"))
                .andExpect(jsonPath("$.nombre").value("q.prueba.ep2"));

        verify(administrador).crearCola("q.prueba.ep2");
    }

    @Test
    @DisplayName("Crear un exchange con tipo valido devuelve 201")
    void crearExchangeValido() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/exchanges")
                        .param("exchangeName", "ex.prueba.ep2")
                        .param("type", "topic"))
                .andExpect(status().isCreated());

        verify(administrador).crearExchange("ex.prueba.ep2", TipoExchange.TOPIC);
    }

    @Test
    @DisplayName("Crear un binding con los tres parametros devuelve 201")
    void crearBindingValido() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/bindings")
                        .param("queueName", "q.prueba.ep2")
                        .param("exchangeName", NombresDelCaso.EXCHANGE_DIRECT)
                        .param("routingKey", "prueba.rk"))
                .andExpect(status().isCreated());

        verify(administrador).crearBinding("q.prueba.ep2", NombresDelCaso.EXCHANGE_DIRECT, "prueba.rk");
    }

    // -----------------------------------------------------------------------
    // Validacion: HTTP 400
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Cola sin nombre: 400 y el broker no se toca")
    void colaSinNombre() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/queues"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("queueName"));

        verifyNoInteractions(administrador);
    }

    @Test
    @DisplayName("Cola con nombre en blanco: 400, no es lo mismo que sin nombre pero tampoco vale")
    void colaConNombreEnBlanco() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/queues").param("queueName", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("queueName"));

        verify(administrador, never()).crearCola(anyString());
    }

    @Test
    @DisplayName("Exchange sin tipo: 400")
    void exchangeSinTipo() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/exchanges").param("exchangeName", "ex.prueba"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("type"));

        verifyNoInteractions(administrador);
    }

    @Test
    @DisplayName("Exchange con tipo inexistente: 400 que enumera los validos")
    void exchangeConTipoInvalido() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/exchanges")
                        .param("exchangeName", "ex.prueba")
                        .param("type", "directo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("direct")));

        verify(administrador, never()).crearExchange(anyString(), any());
    }

    @Test
    @DisplayName("Binding sin exchange: 400")
    void bindingSinExchange() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/bindings")
                        .param("queueName", "q.prueba.ep2")
                        .param("routingKey", "prueba.rk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("exchangeName"));

        verifyNoInteractions(administrador);
    }

    @Test
    @DisplayName("Binding sin routing key: 400")
    void bindingSinRoutingKey() throws Exception {
        mockMvc.perform(post("/api/rabbitmq/bindings")
                        .param("queueName", "q.prueba.ep2")
                        .param("exchangeName", NombresDelCaso.EXCHANGE_DIRECT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("routingKey"));

        verifyNoInteractions(administrador);
    }

    @Test
    @DisplayName("Eliminar un binding incompleto tambien es 400")
    void eliminarBindingIncompleto() throws Exception {
        mockMvc.perform(delete("/api/rabbitmq/bindings")
                        .param("exchangeName", NombresDelCaso.EXCHANGE_DIRECT)
                        .param("routingKey", "prueba.rk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parametro").value("queueName"));

        verifyNoInteractions(administrador);
    }

    // -----------------------------------------------------------------------
    // Consulta, purga y eliminacion
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Consultar una cola devuelve sus mensajes y consumidores")
    void consultarCola() throws Exception {
        when(administrador.consultarCola(NombresDelCaso.QUEUE_EMAIL))
                .thenReturn(new InfoCola(NombresDelCaso.QUEUE_EMAIL, 7, 1));

        mockMvc.perform(get("/api/rabbitmq/queues/{nombre}", NombresDelCaso.QUEUE_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value(NombresDelCaso.QUEUE_EMAIL))
                .andExpect(jsonPath("$.mensajes").value(7))
                .andExpect(jsonPath("$.consumidores").value(1));
    }

    @Test
    @DisplayName("Consultar una cola que no existe devuelve 404, no 500")
    void consultarColaInexistente() throws Exception {
        when(administrador.consultarCola("q.no.existe"))
                .thenThrow(new RecursoNoEncontradoException("La cola q.no.existe no existe en el broker"));

        mockMvc.perform(get("/api/rabbitmq/queues/{nombre}", "q.no.existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("no existe")));
    }

    @Test
    @DisplayName("Purgar informa cuantos mensajes se descartaron")
    void purgarCola() throws Exception {
        when(administrador.purgarCola(NombresDelCaso.DLQ_EMAIL)).thenReturn(3);

        mockMvc.perform(post("/api/rabbitmq/queues/{nombre}/purge", NombresDelCaso.DLQ_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operacion").value("purgar"))
                .andExpect(jsonPath("$.detalle").value("3 mensajes descartados"));
    }

    @Test
    @DisplayName("Eliminar una cola responde 200, y es idempotente")
    void eliminarCola() throws Exception {
        mockMvc.perform(delete("/api/rabbitmq/queues/{nombre}", "q.prueba.ep2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operacion").value("eliminar"))
                .andExpect(jsonPath("$.nombre").value("q.prueba.ep2"));

        verify(administrador).eliminarCola("q.prueba.ep2");
    }
}
