package pedidos360.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * Declara Content-Length en todas las respuestas en lugar de usar
 * Transfer-Encoding: chunked.
 *
 * Motivo concreto: Tomcat cierra la conexion despues de ciertos codigos de
 * estado (400, 408, 411, 413, 414, 500, 501 y 503), porque suelen indicar un
 * error de protocolo y el estado de la conexion deja de ser confiable. Si la
 * respuesta ademas va en chunked, sin longitud declarada, el cliente se
 * encuentra la conexion cerrada antes de terminar de leer y entrega un cuerpo
 * vacio.
 *
 * Es el mismo filtro de orders, catalog y rabbitadmin. En el BFF importa porque
 * por aqui pasa, hacia el API Gateway, el 400 de rabbitadmin por queueName
 * vacio: lo que la prueba empirica pide mostrar en Postman, con el cuerpo que
 * nombra el parametro faltante.
 *
 * Al bufferear la respuesta y fijar Content-Length, el cliente sabe cuantos
 * bytes esperar y alcanza a leerlos antes del cierre. Los cuerpos de esta API
 * son de unos pocos kilobytes, asi que el costo de memoria es irrelevante.
 */
@Component
public class FiltroContentLength extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        ContentCachingResponseWrapper envoltorio = new ContentCachingResponseWrapper(respuesta);
        try {
            cadena.doFilter(peticion, envoltorio);
        } finally {
            // Vuelca el cuerpo bufereado y, al conocer su tamano, fija Content-Length.
            envoltorio.copyBodyToResponse();
        }
    }
}
