package pedidos360.bff.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    @Value("${microservicios.catalog.base-url}")
    private String catalogBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping
    public ResponseEntity<?> getCatalog(
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String soloActivos) {
        
        String url = catalogBaseUrl + "/api/catalog?";
        if (categoria != null) url += "categoria=" + categoria + "&";
        if (soloActivos != null) url += "soloActivos=" + soloActivos;

        try {
            return restTemplate.getForEntity(url, String.class);
        } catch (HttpStatusCodeException e) {
            // Propagación exacta de errores
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
    }

    @PreAuthorize("hasAuthority('ROLE_Admin')")
    @PostMapping
    public ResponseEntity<?> createProduct(@RequestBody java.util.Map<String, Object> payload) {
        try {
            return restTemplate.postForEntity(catalogBaseUrl + "/api/catalog", payload, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).headers(e.getResponseHeaders()).body(e.getResponseBodyAsString());
        }
    }
}

