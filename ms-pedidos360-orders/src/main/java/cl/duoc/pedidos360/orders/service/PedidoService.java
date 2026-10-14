package cl.duoc.pedidos360.orders.service;

import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.client.dto.LineaStockDto;
import cl.duoc.pedidos360.orders.client.dto.ProductoCatalogoDto;
import cl.duoc.pedidos360.orders.domain.EstadoPedido;
import cl.duoc.pedidos360.orders.domain.Pedido;
import cl.duoc.pedidos360.orders.domain.PedidoItem;
import cl.duoc.pedidos360.orders.exception.RecursoNoEncontradoException;
import cl.duoc.pedidos360.orders.exception.ReglaNegocioException;
import cl.duoc.pedidos360.orders.messaging.PublicadorComandos;
import cl.duoc.pedidos360.orders.repository.PedidoRepository;
import cl.duoc.pedidos360.orders.web.dto.CrearPedidoRequest;
import cl.duoc.pedidos360.orders.web.dto.LineaPedidoRequest;
import cl.duoc.pedidos360.orders.web.dto.PedidoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);
    private static final DateTimeFormatter FORMATO_FECHA_CODIGO = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PedidoRepository repositorio;
    private final CatalogClient catalogo;
    private final PublicadorComandos publicador;

    public PedidoService(PedidoRepository repositorio, CatalogClient catalogo,
                         PublicadorComandos publicador) {
        this.repositorio = repositorio;
        this.catalogo = catalogo;
        this.publicador = publicador;
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listar(String clienteEmail, EstadoPedido estado) {
        String filtroEmail = StringUtils.hasText(clienteEmail) ? clienteEmail : null;
        return repositorio.buscar(filtroEmail, estado).stream()
                .map(PedidoResponse::desde)
                .toList();
    }

    @Transactional(readOnly = true)
    public PedidoResponse obtener(Long id) {
        return PedidoResponse.desde(buscarConItems(id));
    }

    @Transactional(readOnly = true)
    public PedidoResponse obtenerPorCodigo(String codigo) {
        Pedido pedido = repositorio.findWithItemsByCodigo(codigo)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el pedido " + codigo));
        return PedidoResponse.desde(pedido);
    }

    /**
     * Crea el pedido en estado CREADO.
     *
     * La verificacion de stock aqui es informativa, no una reserva: el descuento
     * real ocurre al aceptar. Es una decision deliberada, porque un carrito
     * abandonado no debe bloquear inventario.
     */
    @Transactional
    public PedidoResponse crear(CrearPedidoRequest request) {
        Map<Long, Integer> lineas = consolidar(request.items());

        Pedido pedido = new Pedido(
                generarCodigo(),
                request.clienteEmail(),
                request.clienteNombre(),
                request.direccionEntrega());
        pedido.setObservacion(request.observacion());

        List<String> problemas = new ArrayList<>();
        for (Map.Entry<Long, Integer> linea : lineas.entrySet()) {
            ProductoCatalogoDto producto = catalogo.obtenerProducto(linea.getKey());
            int cantidad = linea.getValue();

            if (!Boolean.TRUE.equals(producto.activo())) {
                problemas.add("El producto " + producto.sku() + " no esta disponible para la venta");
                continue;
            }
            if (producto.stock() == null || producto.stock() < cantidad) {
                problemas.add("Stock insuficiente para " + producto.sku()
                        + ": solicitado " + cantidad + ", disponible " + producto.stock());
                continue;
            }
            pedido.agregarItem(new PedidoItem(
                    producto.id(), producto.sku(), producto.nombre(), cantidad, producto.precio()));
        }

        if (!problemas.isEmpty()) {
            throw new ReglaNegocioException("No es posible crear el pedido", problemas);
        }

        Pedido guardado = repositorio.save(pedido);
        log.info("Pedido creado. codigo={} cliente={} total={}",
                guardado.getCodigo(), guardado.getClienteEmail(), guardado.getTotal());
        return PedidoResponse.desde(guardado);
    }

    /**
     * CREADO -> ACEPTADO. Es la unica transicion que descuenta stock en el catalogo.
     *
     * Orden deliberado: primero validamos la transicion y persistimos el cambio con
     * flush, y solo despues llamamos al catalogo. Si el catalogo devuelve 409 por
     * falta de stock, la excepcion propaga, esta transaccion hace rollback y el
     * pedido vuelve a quedar en CREADO: no queda estado inconsistente.
     *
     * Ventana residual conocida: si el catalogo descuenta y el commit local falla
     * despues, el stock queda descontado sin pedido aceptado. Resolverlo de verdad
     * exige un patron saga con mensajeria, fuera del alcance de esta entrega.
     */
    @Transactional
    public PedidoResponse aceptar(Long id) {
        Pedido pedido = buscarConItems(id);
        pedido.cambiarEstado(EstadoPedido.ACEPTADO);
        repositorio.saveAndFlush(pedido);

        catalogo.reservarStock(pedido.getCodigo(), aLineasDeStock(pedido));

        log.info("Pedido aceptado y stock descontado. codigo={}", pedido.getCodigo());

        // La publicacion va al final, cuando la transicion y el descuento de stock
        // ya tuvieron exito. Si el catalogo rechaza por falta de stock la excepcion
        // propaga antes de llegar aqui y no se emite ningun mensaje: nunca se
        // notifica al cliente un pedido que en realidad no se acepto.
        //
        // Aceptar genera dos mensajes porque son dos trabajos distintos: avisar al
        // cliente y encolar el ticket en cocina. Comparten correlationId -el codigo
        // del pedido- para poder seguirlos juntos en los logs del consumidor.
        publicador.publicarEmail(pedido);
        publicador.publicarKitchen(pedido);

        return PedidoResponse.desde(pedido);
    }

    /**
     * ACEPTADO -> EN_PREPARACION. No publica nada: el ticket de cocina ya se
     * emitio al aceptar, y repetirlo aqui seria duplicar el trabajo.
     */
    @Transactional
    public PedidoResponse preparar(Long id) {
        return PedidoResponse.desde(avanzar(id, EstadoPedido.EN_PREPARACION));
    }

    /**
     * EN_PREPARACION -> DESPACHADO. No se puede despachar sin haber aceptado.
     *
     * Avisa al cliente que el pedido salio: segundo mensaje por cmd.direct con
     * la routing key email.send, de nuevo hacia q.cmd.email.
     */
    @Transactional
    public PedidoResponse despachar(Long id) {
        Pedido pedido = avanzar(id, EstadoPedido.DESPACHADO);
        publicador.publicarEmail(pedido);
        return PedidoResponse.desde(pedido);
    }

    /**
     * DESPACHADO -> ENTREGADO. Cierra el ciclo pidiendo el documento de venta
     * por cmd.direct con la routing key invoice.gen hacia q.cmd.invoice.
     */
    @Transactional
    public PedidoResponse entregar(Long id) {
        Pedido pedido = avanzar(id, EstadoPedido.ENTREGADO);
        publicador.publicarInvoice(pedido);
        return PedidoResponse.desde(pedido);
    }

    /**
     * CREADO|ACEPTADO -> CANCELADO. Si el pedido ya habia descontado stock, se repone.
     * Si la reposicion falla, la excepcion propaga y la cancelacion tambien se revierte.
     */
    @Transactional
    public PedidoResponse cancelar(Long id, String motivo) {
        Pedido pedido = buscarConItems(id);
        boolean debeReponerStock = pedido.tieneStockReservado();

        pedido.cambiarEstado(EstadoPedido.CANCELADO);
        if (StringUtils.hasText(motivo)) {
            pedido.setObservacion(motivo);
        }
        repositorio.saveAndFlush(pedido);

        if (debeReponerStock) {
            catalogo.liberarStock(pedido.getCodigo(), aLineasDeStock(pedido));
        }

        log.info("Pedido cancelado. codigo={} reponeStock={}", pedido.getCodigo(), debeReponerStock);
        return PedidoResponse.desde(pedido);
    }

    /**
     * Devuelve la entidad y no el DTO porque quien llama necesita el Pedido para
     * construir el mensaje: el productor arma el cuerpo desde el dominio.
     */
    private Pedido avanzar(Long id, EstadoPedido destino) {
        Pedido pedido = buscarConItems(id);
        EstadoPedido origen = pedido.getEstado();
        pedido.cambiarEstado(destino);
        Pedido guardado = repositorio.save(pedido);
        log.info("Pedido {} cambio de {} a {}", guardado.getCodigo(), origen, destino);
        return guardado;
    }

    private Pedido buscarConItems(Long id) {
        return repositorio.findWithItemsById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el pedido con id " + id));
    }

    private List<LineaStockDto> aLineasDeStock(Pedido pedido) {
        return pedido.getItems().stream()
                .map(item -> new LineaStockDto(item.getProductoId(), item.getCantidad()))
                .toList();
    }

    /** Suma las cantidades si el cliente envio el mismo producto en varias lineas. */
    private Map<Long, Integer> consolidar(List<LineaPedidoRequest> items) {
        Map<Long, Integer> consolidado = new LinkedHashMap<>();
        for (LineaPedidoRequest item : items) {
            consolidado.merge(item.productoId(), item.cantidad(), Integer::sum);
        }
        return consolidado;
    }

    private String generarCodigo() {
        String candidato;
        do {
            candidato = "PED-" + LocalDate.now().format(FORMATO_FECHA_CODIGO) + "-"
                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (repositorio.existsByCodigo(candidato));
        return candidato;
    }
}
