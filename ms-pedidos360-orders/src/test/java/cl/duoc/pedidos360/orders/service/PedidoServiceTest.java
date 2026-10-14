package cl.duoc.pedidos360.orders.service;

import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.client.dto.ProductoCatalogoDto;
import cl.duoc.pedidos360.orders.domain.EstadoPedido;
import cl.duoc.pedidos360.orders.domain.Pedido;
import cl.duoc.pedidos360.orders.exception.ReglaNegocioException;
import cl.duoc.pedidos360.orders.exception.TransicionInvalidaException;
import cl.duoc.pedidos360.orders.messaging.PublicadorComandos;
import cl.duoc.pedidos360.orders.web.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.orders.web.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.orders.web.dto.PedidoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class PedidoServiceTest {

    private static final Long ID_DISPONIBLE = 1L;
    private static final Long ID_CASI_AGOTADO = 2L;
    private static final Long ID_INACTIVO = 3L;

    @Autowired
    private PedidoService servicio;

    /** El catalogo es otro microservicio: en las pruebas se simula. */
    @MockBean
    private CatalogClient catalogo;

    /**
     * El productor tambien se simula. No es solo comodidad: con el productor real
     * estas pruebas intentarian abrir una conexion AMQP y solo pasarian si el
     * cluster estuviera levantado, lo que convertiria una prueba unitaria en una
     * prueba de integracion disfrazada. Con el mock, ademas, se puede afirmar
     * QUE se publico y cuando.
     */
    @MockBean
    private PublicadorComandos publicador;

    @BeforeEach
    void simularCatalogo() {
        when(catalogo.obtenerProducto(ID_DISPONIBLE)).thenReturn(new ProductoCatalogoDto(
                ID_DISPONIBLE, "SKU-DISP", "Notebook", new BigDecimal("500000.00"), 50, "COMPUTACION", true));
        when(catalogo.obtenerProducto(ID_CASI_AGOTADO)).thenReturn(new ProductoCatalogoDto(
                ID_CASI_AGOTADO, "SKU-POCO", "Monitor", new BigDecimal("120000.00"), 2, "COMPUTACION", true));
        when(catalogo.obtenerProducto(ID_INACTIVO)).thenReturn(new ProductoCatalogoDto(
                ID_INACTIVO, "SKU-BAJA", "Producto de baja", new BigDecimal("1000.00"), 99, "TEST", false));
    }

    private PedidoResponse crearPedidoSimple(int cantidad) {
        return servicio.crear(new CrearPedidoRequest(
                "cliente@duoc.cl", "Cliente Prueba", "Av. Siempre Viva 742", null,
                List.of(new LineaPedidoRequest(ID_DISPONIBLE, cantidad))));
    }

    // -----------------------------------------------------------------------
    // Creacion
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Un pedido nace en CREADO y calcula el total")
    void crearPedido() {
        PedidoResponse pedido = crearPedidoSimple(2);

        assertThat(pedido.id()).isNotNull();
        assertThat(pedido.codigo()).startsWith("PED-");
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(pedido.total()).isEqualByComparingTo("1000000.00");
        assertThat(pedido.items()).hasSize(1);
        assertThat(pedido.transicionesPermitidas())
                .containsExactlyInAnyOrder(EstadoPedido.ACEPTADO, EstadoPedido.CANCELADO);
    }

    @Test
    @DisplayName("Crear un pedido NO descuenta stock: la reserva ocurre al aceptar")
    void crearNoReservaStock() {
        crearPedidoSimple(2);
        verify(catalogo, never()).reservarStock(anyString(), any());
    }

    @Test
    @DisplayName("El precio queda congelado en la linea del pedido")
    void precioCongelado() {
        PedidoResponse pedido = crearPedidoSimple(1);

        assertThat(pedido.items().get(0).precioUnitario()).isEqualByComparingTo("500000.00");
        assertThat(pedido.items().get(0).sku()).isEqualTo("SKU-DISP");
        assertThat(pedido.items().get(0).nombreProducto()).isEqualTo("Notebook");
    }

    @Test
    @DisplayName("No se puede pedir mas de lo que hay en catalogo")
    void stockInsuficienteAlCrear() {
        assertThatThrownBy(() -> servicio.crear(new CrearPedidoRequest(
                "cliente@duoc.cl", null, null, null,
                List.of(new LineaPedidoRequest(ID_CASI_AGOTADO, 10)))))
                .isInstanceOf(ReglaNegocioException.class)
                .satisfies(ex -> assertThat(((ReglaNegocioException) ex).getDetalles().get(0))
                        .contains("SKU-POCO")
                        .contains("disponible 2"));
    }

    @Test
    @DisplayName("No se puede pedir un producto dado de baja")
    void productoInactivoAlCrear() {
        assertThatThrownBy(() -> servicio.crear(new CrearPedidoRequest(
                "cliente@duoc.cl", null, null, null,
                List.of(new LineaPedidoRequest(ID_INACTIVO, 1)))))
                .isInstanceOf(ReglaNegocioException.class)
                .satisfies(ex -> assertThat(((ReglaNegocioException) ex).getDetalles().get(0))
                        .contains("no esta disponible"));
    }

    @Test
    @DisplayName("Las lineas repetidas del mismo producto se consolidan en una")
    void lineasRepetidasSeConsolidan() {
        PedidoResponse pedido = servicio.crear(new CrearPedidoRequest(
                "cliente@duoc.cl", null, null, null,
                List.of(
                        new LineaPedidoRequest(ID_DISPONIBLE, 1),
                        new LineaPedidoRequest(ID_DISPONIBLE, 3))));

        assertThat(pedido.items()).hasSize(1);
        assertThat(pedido.items().get(0).cantidad()).isEqualTo(4);
        assertThat(pedido.total()).isEqualByComparingTo("2000000.00");
    }

    // -----------------------------------------------------------------------
    // Maquina de estados
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Aceptar pasa a ACEPTADO y descuenta stock en el catalogo")
    void aceptarDescuentaStock() {
        PedidoResponse pedido = crearPedidoSimple(2);

        PedidoResponse aceptado = servicio.aceptar(pedido.id());

        assertThat(aceptado.estado()).isEqualTo(EstadoPedido.ACEPTADO);
        verify(catalogo, times(1)).reservarStock(anyString(), any());
    }

    @Test
    @DisplayName("No se puede despachar sin haber aceptado")
    void noSePuedeDespacharSinAceptar() {
        PedidoResponse pedido = crearPedidoSimple(1);

        assertThatThrownBy(() -> servicio.despachar(pedido.id()))
                .isInstanceOf(TransicionInvalidaException.class)
                .hasMessageContaining("no puede pasar de CREADO a DESPACHADO");
    }

    @Test
    @DisplayName("Si el catalogo rechaza la reserva, el pedido no queda aceptado")
    void reservaRechazadaNoAcepta() {
        PedidoResponse pedido = crearPedidoSimple(2);
        doThrow(new ReglaNegocioException("Stock insuficiente"))
                .when(catalogo).reservarStock(anyString(), any());

        assertThatThrownBy(() -> servicio.aceptar(pedido.id()))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    @DisplayName("Flujo completo: CREADO -> ACEPTADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO")
    void flujoCompleto() {
        PedidoResponse pedido = crearPedidoSimple(1);
        Long id = pedido.id();

        assertThat(servicio.aceptar(id).estado()).isEqualTo(EstadoPedido.ACEPTADO);
        assertThat(servicio.preparar(id).estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
        assertThat(servicio.despachar(id).estado()).isEqualTo(EstadoPedido.DESPACHADO);

        PedidoResponse entregado = servicio.entregar(id);
        assertThat(entregado.estado()).isEqualTo(EstadoPedido.ENTREGADO);
        assertThat(entregado.transicionesPermitidas()).isEmpty();
    }

    @Test
    @DisplayName("Un pedido entregado ya no admite cambios")
    void entregadoEsFinal() {
        PedidoResponse pedido = crearPedidoSimple(1);
        Long id = pedido.id();
        servicio.aceptar(id);
        servicio.preparar(id);
        servicio.despachar(id);
        servicio.entregar(id);

        assertThatThrownBy(() -> servicio.cancelar(id, "tarde"))
                .isInstanceOf(TransicionInvalidaException.class)
                .hasMessageContaining("ENTREGADO");
    }

    // -----------------------------------------------------------------------
    // Cancelacion y reposicion de stock
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Cancelar un pedido CREADO no repone stock, porque nunca se descontó")
    void cancelarCreadoNoReponeStock() {
        PedidoResponse pedido = crearPedidoSimple(2);

        PedidoResponse cancelado = servicio.cancelar(pedido.id(), "El cliente se arrepintio");

        assertThat(cancelado.estado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThat(cancelado.observacion()).isEqualTo("El cliente se arrepintio");
        verify(catalogo, never()).liberarStock(anyString(), any());
    }

    @Test
    @DisplayName("Cancelar un pedido ACEPTADO repone el stock en el catalogo")
    void cancelarAceptadoReponeStock() {
        PedidoResponse pedido = crearPedidoSimple(2);
        servicio.aceptar(pedido.id());

        PedidoResponse cancelado = servicio.cancelar(pedido.id(), "Sin stock en bodega");

        assertThat(cancelado.estado()).isEqualTo(EstadoPedido.CANCELADO);
        verify(catalogo, times(1)).liberarStock(anyString(), any());
    }

    @Test
    @DisplayName("No se puede cancelar un pedido ya despachado")
    void noSeCancelaDespachado() {
        PedidoResponse pedido = crearPedidoSimple(1);
        Long id = pedido.id();
        servicio.aceptar(id);
        servicio.preparar(id);
        servicio.despachar(id);

        assertThatThrownBy(() -> servicio.cancelar(id, "muy tarde"))
                .isInstanceOf(TransicionInvalidaException.class);
    }

    // -----------------------------------------------------------------------
    // Consultas
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("El filtro por cliente y por estado funciona")
    void filtrosDeListado() {
        PedidoResponse deCliente = crearPedidoSimple(1);
        servicio.crear(new CrearPedidoRequest(
                "otro@duoc.cl", null, null, null,
                List.of(new LineaPedidoRequest(ID_DISPONIBLE, 1))));

        assertThat(servicio.listar("cliente@duoc.cl", null))
                .extracting(PedidoResponse::id)
                .contains(deCliente.id());

        assertThat(servicio.listar("otro@duoc.cl", null))
                .extracting(PedidoResponse::id)
                .doesNotContain(deCliente.id());

        assertThat(servicio.listar(null, EstadoPedido.ENTREGADO))
                .extracting(PedidoResponse::id)
                .doesNotContain(deCliente.id());
    }

    @Test
    @DisplayName("El filtro por email no distingue mayusculas")
    void filtroEmailIgnoraMayusculas() {
        PedidoResponse pedido = crearPedidoSimple(1);

        assertThat(servicio.listar("CLIENTE@DUOC.CL", null))
                .extracting(PedidoResponse::id)
                .contains(pedido.id());
    }

    @Test
    @DisplayName("Se puede recuperar el pedido por su codigo")
    void buscarPorCodigo() {
        PedidoResponse pedido = crearPedidoSimple(1);

        assertThat(servicio.obtenerPorCodigo(pedido.codigo()).id()).isEqualTo(pedido.id());
    }

    // -----------------------------------------------------------------------
    // Mensajeria (EP2)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Crear un pedido no publica nada: todavia no hay nada que notificar")
    void crearNoPublica() {
        crearPedidoSimple(2);

        verifyNoInteractions(publicador);
    }

    @Test
    @DisplayName("Aceptar publica dos mensajes: notificacion al cliente y ticket de cocina")
    void aceptarPublicaEmailYKitchen() {
        PedidoResponse pedido = crearPedidoSimple(2);

        servicio.aceptar(pedido.id());

        verify(publicador, times(1)).publicarEmail(any());
        verify(publicador, times(1)).publicarKitchen(any());
        verify(publicador, never()).publicarInvoice(any());
    }

    @Test
    @DisplayName("Si el catalogo rechaza la reserva, no se publica ningun mensaje")
    void reservaRechazadaNoPublica() {
        PedidoResponse pedido = crearPedidoSimple(2);
        doThrow(new ReglaNegocioException("Stock insuficiente"))
                .when(catalogo).reservarStock(anyString(), any());

        assertThatThrownBy(() -> servicio.aceptar(pedido.id()))
                .isInstanceOf(ReglaNegocioException.class);

        // Esta es la razon por la que la publicacion va al final del metodo: no se
        // puede avisar al cliente que su pedido fue aceptado si en realidad no lo fue.
        verifyNoInteractions(publicador);
    }

    @Test
    @DisplayName("Preparar no publica: el ticket de cocina ya salio al aceptar")
    void prepararNoPublica() {
        PedidoResponse pedido = crearPedidoSimple(1);
        servicio.aceptar(pedido.id());
        clearInvocations(publicador);

        servicio.preparar(pedido.id());

        verifyNoInteractions(publicador);
    }

    @Test
    @DisplayName("Despachar publica la notificacion de envio y entregar el documento")
    void despacharYEntregarPublican() {
        PedidoResponse pedido = crearPedidoSimple(1);
        Long id = pedido.id();
        servicio.aceptar(id);
        servicio.preparar(id);
        clearInvocations(publicador);

        servicio.despachar(id);
        verify(publicador, times(1)).publicarEmail(any());
        verify(publicador, never()).publicarInvoice(any());

        servicio.entregar(id);
        verify(publicador, times(1)).publicarInvoice(any());
    }

    @Test
    @DisplayName("Una transicion invalida no publica nada")
    void transicionInvalidaNoPublica() {
        PedidoResponse pedido = crearPedidoSimple(1);

        assertThatThrownBy(() -> servicio.despachar(pedido.id()))
                .isInstanceOf(TransicionInvalidaException.class);

        verifyNoInteractions(publicador);
    }

    @Test
    @DisplayName("El mensaje se arma con el pedido ya en su nuevo estado")
    void elMensajeLlevaElEstadoNuevo() {
        PedidoResponse pedido = crearPedidoSimple(1);

        servicio.aceptar(pedido.id());

        ArgumentCaptor<Pedido> capturado = ArgumentCaptor.forClass(Pedido.class);
        verify(publicador).publicarEmail(capturado.capture());
        assertThat(capturado.getValue().getEstado()).isEqualTo(EstadoPedido.ACEPTADO);
        assertThat(capturado.getValue().getCodigo()).isEqualTo(pedido.codigo());
        assertThat(capturado.getValue().getItems()).hasSize(1);
    }

    @Test
    @DisplayName("Cancelar no publica: en esta entrega la cancelacion no genera mensajes")
    void cancelarNoPublica() {
        PedidoResponse pedido = crearPedidoSimple(2);
        servicio.aceptar(pedido.id());
        clearInvocations(publicador);

        servicio.cancelar(pedido.id(), "El cliente se arrepintio");

        verifyNoInteractions(publicador);
    }
}
