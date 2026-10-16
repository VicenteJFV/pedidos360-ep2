package pedidos360.bff.controllers;

import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Value("${microservicios.orders.base-url}")
    private String ordersBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping
    public ResponseEntity<?> getOrders(
            @RequestParam(required = false) String clienteEmail,
            @RequestParam(required = false) String estado,
            @AuthenticationPrincipal Jwt jwt) {
        
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean isCliente = roles != null && roles.contains("Cliente");
        boolean isAdmin = roles != null && roles.contains("Admin");
        
        String emailAConsultar = clienteEmail;
        if (isCliente && !isAdmin) {
            emailAConsultar = jwt.getClaimAsString("preferred_username");
        }

        String url = ordersBaseUrl + "/api/orders?";
        if (emailAConsultar != null) url += "clienteEmail=" + emailAConsultar + "&";
        if (estado != null) url += "estado=" + estado;

        try {
            return restTemplate.getForEntity(url, String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        }
    }

    @PostMapping
    public ResponseEntity<?> createOrder(
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal Jwt jwt) {
        
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean isCliente = roles != null && roles.contains("Cliente");
        boolean isAdmin = roles != null && roles.contains("Admin");

        if (isCliente && !isAdmin) {
            payload.put("clienteEmail", jwt.getClaimAsString("preferred_username"));
        }

        try {
            return restTemplate.postForEntity(ordersBaseUrl + "/api/orders", payload, String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        }
    }

    @PreAuthorize("hasAuthority('ROLE_Admin')")
    @PutMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable String id) {
        try {
            return restTemplate.exchange(ordersBaseUrl + "/api/orders/" + id + "/accept", org.springframework.http.HttpMethod.PUT, null, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        }
    }

    @PreAuthorize("hasAuthority('ROLE_Admin')")
    @PutMapping("/{id}/ship")
    public ResponseEntity<?> ship(@PathVariable String id) {
        try {
            return restTemplate.exchange(ordersBaseUrl + "/api/orders/" + id + "/ship", org.springframework.http.HttpMethod.PUT, null, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        }
    }

    @PreAuthorize("hasAuthority('ROLE_Admin')")
    @PutMapping("/{id}/prepare")
    public ResponseEntity<?> prepare(@PathVariable String id) {
        try {
            return restTemplate.exchange(ordersBaseUrl + "/api/orders/" + id + "/prepare", org.springframework.http.HttpMethod.PUT, null, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        }
    }

    @PreAuthorize("hasAuthority('ROLE_Admin')")
    @PutMapping("/{id}/deliver")
    public ResponseEntity<?> deliver(@PathVariable String id) {
        try {
            return restTemplate.exchange(ordersBaseUrl + "/api/orders/" + id + "/deliver", org.springframework.http.HttpMethod.PUT, null, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        }
    }
}