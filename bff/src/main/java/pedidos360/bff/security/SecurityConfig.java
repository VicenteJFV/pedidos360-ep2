package pedidos360.bff.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
@Configuration
@EnableWebSecurity
@EnableMethodSecurity 

public class SecurityConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return authorities;
        });
        return converter;
    }

    /**
     * Sin configuracion de CORS en el BFF: CORS se resuelve en AWS, en el API
     * Gateway (--cors-configuration), que responde el preflight OPTIONS por si
     * mismo y agrega las cabeceras Access-Control-* a las respuestas. Asi lo
     * pidio el profesor en la evaluacion de EP1, y es lo coherente con que el
     * Gateway sea el unico punto de entrada: la politica de que origenes pueden
     * llamar a la API vive en un solo lugar.
     *
     * Tenerla tambien aqui no solo duplicaba, sino que rompia: al BFF la peticion
     * le llega con Host = la EC2 y Origin = el dominio del Gateway, Spring los ve
     * distintos y, si el origen no estaba en su lista, rechazaba con 403.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }
}