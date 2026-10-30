package io.apistudio.cp.web;

import io.apistudio.cp.service.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulasi API Engine (gateway) pada MVP (BRD Bagian 5.3).
 *
 * <p>Endpoint runtime berada di prefix {@code /gw/**} agar terpisah jelas dari
 * Control Plane API ({@code /api/**}). Di produksi, prefix ini digantikan
 * gateway nyata yang meneruskan request ke Flow Engine.
 *
 * <p>Endpoint ini tidak memakai RBAC Studio: autentikasi klien mengikuti
 * kebijakan per API (API key/OAuth2/JWT/HMAC) yang dikonfigurasi pada FR-API-03.
 */
@RestController
public class GatewayController {

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    /**
     * Semua method HTTP diteruskan ke gateway.
     *
     * <p>Contoh: {@code POST /gw/v1.0/transfer-intrabank?environment=production}
     */
    @RequestMapping(value = "/gw/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS
    })
    public ResponseEntity<Object> handle(HttpServletRequest request,
                                         @RequestHeader Map<String, String> headers,
                                         @RequestParam Map<String, String> query,
                                         @RequestBody(required = false) Object body) {
        String fullPath = request.getRequestURI();
        String path = fullPath.startsWith("/gw") ? fullPath.substring(3) : fullPath;
        if (path.isEmpty()) {
            path = "/";
        }

        String environment = query.get("environment");
        if (environment == null || environment.isBlank()) {
            environment = request.getHeader("X-Environment");
        }
        if (environment == null || environment.isBlank()) {
            environment = "production";
        }

        Map<String, String> cleanQuery = new LinkedHashMap<>(query);
        cleanQuery.remove("environment");

        Map<String, Object> result = gateway.handle(request.getMethod(), path, environment,
                headers, cleanQuery, body);

        int status = result.get("status") instanceof Number n ? n.intValue() : 200;
        Object responseBody = result.get("body");

        @SuppressWarnings("unchecked")
        Map<String, String> responseHeaders = result.get("headers") instanceof Map<?, ?> map
                ? (Map<String, String>) map
                : Map.of();

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        responseHeaders.forEach((key, value) -> {
            if (key != null && value != null && !key.equalsIgnoreCase("content-length")) {
                builder.header(key, value);
            }
        });
        Object traceId = result.get("traceId");
        if (traceId != null) {
            builder.header("X-Trace-Id", String.valueOf(traceId));
        }
        return builder.body(responseBody == null ? Map.of() : responseBody);
    }

    /** Informasi rute dan header (untuk debugging integrasi). */
    @RequestMapping(value = "/gw/_routes", method = RequestMethod.GET)
    public Object routes(@RequestParam(required = false, defaultValue = "production") String environment) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("environment", environment);
        info.put("message", "Gunakan /gw{path}?environment=" + environment
                + " untuk memanggil API yang sudah dideploy. Autentikasi memakai header X-API-Key "
                + "atau Authorization: Bearer <apiKey> sesuai kebijakan API.");
        return info;
    }
}
