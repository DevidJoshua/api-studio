package io.apistudio.cp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Penanganan error terpusat agar semua respons error konsisten untuk Studio.
 *
 * <p>Pemetaan status eksplisit penting: tanpa handler khusus,
 * {@code NoResourceFoundException} (rute tidak dikenal) dan
 * {@code HttpRequestMethodNotSupportedException} akan jatuh ke handler generik
 * dan salah dilaporkan sebagai 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        // IllegalStateException umumnya menandakan kegagalan proses internal
        // (mis. kompilasi/runtime), sehingga dipetakan ke 422 agar klien tahu
        // ini bukan kesalahan sintaks permintaan.
        return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** Rute tidak dikenal harus 404, bukan 500 (mis. path API salah ketik). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 404);
        body.put("error", "Tidak ada endpoint untuk " + ex.getResourcePath());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** Method HTTP tidak didukung pada rute yang ada harus 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " tidak didukung pada endpoint ini");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Kesalahan tidak tertangani pada Control Plane", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 500);
        body.put("error", "Terjadi kesalahan internal: " + ex.getClass().getSimpleName());
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.internalServerError().body(body);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
