package cl.duoc.pedidos360.rabbitadmin.service;

/** La cola o el exchange que se consulto no existe en el broker. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
