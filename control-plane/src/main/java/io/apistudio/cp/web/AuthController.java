package io.apistudio.cp.web;

import io.apistudio.cp.domain.AppUser;
import io.apistudio.cp.repo.AppUserRepository;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.security.Role;
import io.apistudio.cp.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Autentikasi Studio dan manajemen pengguna (BRD FR-SEC-01).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Panjang minimum password yang diterima saat membuat pengguna. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;
    private final AuthService auth;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(AppUserRepository users, AuthService auth, AuditService audit) {
        this.users = users;
        this.auth = auth;
        this.audit = audit;
    }

    /** Login dengan username/password lokal. */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = stringOf(body.get("username"));
        String password = stringOf(body.get("password"));
        if (username == null || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username dan password wajib diisi");
        }

        AppUser user = users.findByUsername(username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Username atau password salah"));
        if (!user.enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Akun dinonaktifkan");
        }
        if (user.passwordHash == null || !encoder.matches(password, user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username atau password salah");
        }

        user.lastLoginAt = Instant.now();
        users.save(user);

        String token = auth.createToken(user.id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", userView(user));
        return response;
    }

    /** Logout: batalkan token aktif. */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String token = header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7).trim()
                    : header.trim();
            auth.invalidate(token);
        }
        return Map.of("ok", true);
    }

    /** Profil pengguna aktif. */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        CurrentUser user = auth.require(request);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.id());
        view.put("username", user.username());
        view.put("displayName", user.displayName());
        view.put("role", user.role().name());
        view.put("roleLabel", user.roleLabel());
        view.put("permissions", user.role().permissions().stream().map(Enum::name).sorted().toList());
        return view;
    }

    /** Daftar pengguna (butuh USER_MANAGE). */
    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpServletRequest request) {
        auth.require(request, Permission.USER_MANAGE);
        return users.findAll().stream().map(this::userView).toList();
    }

    /** Buat pengguna baru (butuh USER_MANAGE). */
    @PostMapping("/users")
    public Map<String, Object> createUser(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.USER_MANAGE);
        String username = stringOf(body.get("username"));
        String password = stringOf(body.get("password"));
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username wajib diisi");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password minimal " + MIN_PASSWORD_LENGTH + " karakter");
        }
        if (users.existsByUsername(username.trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Username sudah dipakai: " + username);
        }

        String role = String.valueOf(body.getOrDefault("role", "DESIGNER")).toUpperCase(Locale.ROOT);
        if (!Role.names().contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Peran tidak dikenal: " + role + ". Pilihan: " + Role.names());
        }

        AppUser user = new AppUser(username.trim(),
                String.valueOf(body.getOrDefault("displayName", username.trim())),
                String.valueOf(body.getOrDefault("email", username.trim() + "@example.com")),
                role);
        user.passwordHash = encoder.encode(password);
        AppUser saved = users.save(user);

        audit.record(actor, "USER_CREATED", "AppUser", saved.id,
                "Pengguna " + saved.username + " dibuat dengan peran " + saved.role);
        return userView(saved);
    }

    /** Peran dan izin yang tersedia (untuk UI). */
    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return java.util.Arrays.stream(Role.values()).map(role -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", role.name());
            view.put("label", role.label());
            view.put("permissions", role.permissions().stream().map(Enum::name).sorted().toList());
            return view;
        }).toList();
    }

    private Map<String, Object> userView(AppUser user) {
        Role role = Role.parse(user.role);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.id);
        view.put("username", user.username);
        view.put("displayName", user.displayName);
        view.put("email", user.email);
        view.put("role", user.role);
        view.put("roleLabel", role.label());
        // Daftar izin harus disertakan: Studio memakai daftar ini untuk
        // menyembunyikan tab dan tombol, sehingga UI konsisten dengan RBAC
        // backend tanpa perlu menebak dari nama peran.
        view.put("permissions", role.permissions().stream().map(Enum::name).sorted().toList());
        view.put("enabled", user.enabled);
        view.put("systemUser", user.systemUser);
        view.put("createdAt", user.createdAt);
        view.put("lastLoginAt", user.lastLoginAt);
        return view;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
