package io.apistudio.cp.security;

import io.apistudio.cp.repo.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autentikasi sesi dan otorisasi RBAC (BRD FR-SEC-01).
 *
 * <p>Pada MVP, login menghasilkan token acak yang disimpan di memori proses.
 * Token ini cukup untuk alur Studio dan mudah diuji. Untuk produksi, sesi
 * diganti JWT bertanda tangan atau SSO/OIDC: satu-satunya perubahan adalah
 * implementasi {@link #resolve(HttpServletRequest)}.
 *
 * <p>Sesi in-memory berarti restart Control Plane memaksa login ulang. Ini
 * trade-off yang disengaja untuk MVP (tanpa Redis); konsekuensinya dicatat di
 * dokumentasi.
 */
@Service
public class AuthService {

    /** Sesi: token &rarr; id pengguna + waktu pembuatan. */
    private record Session(String userId, long createdAt) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AppUserRepository users;

    public AuthService(AppUserRepository users) {
        this.users = users;
    }

    /** Buat token sesi untuk pengguna. */
    public String createToken(String userId) {
        String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((userId + ":" + System.nanoTime() + ":" + java.util.UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sessions.put(token, new Session(userId, System.currentTimeMillis()));
        return token;
    }

    /** Batalkan token. */
    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Jumlah sesi aktif (diagnostik). */
    public int activeSessions() {
        return sessions.size();
    }

    /** Resolusi pengguna dari header Authorization. */
    public Optional<CurrentUser> resolve(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        return users.findById(session.userId())
                .filter(user -> user.enabled)
                .map(user -> new CurrentUser(
                        user.id,
                        user.username,
                        user.displayName == null ? user.username : user.displayName,
                        Role.parse(user.role)));
    }

    /** Wajib terautentikasi, jika tidak 401. */
    public CurrentUser require(HttpServletRequest request) {
        return resolve(request).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Sesi tidak valid. Silakan login kembali."));
    }

    /** Wajib memiliki izin tertentu, jika tidak 401/403. */
    public CurrentUser require(HttpServletRequest request, Permission permission) {
        CurrentUser user = require(request);
        if (!user.can(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Peran " + user.role() + " tidak memiliki izin " + permission);
        }
        return user;
    }

    /** Ambil token dari header {@code Authorization: Bearer ...}. */
    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
