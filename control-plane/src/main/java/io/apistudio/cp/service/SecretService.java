package io.apistudio.cp.service;

import io.apistudio.cp.domain.SecretRef;
import io.apistudio.cp.repo.SecretRefRepository;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.engine.runtime.SecretResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manajemen secret terpusat (BRD FR-SEC-02, NFR-04).
 *
 * <p>Prinsip:
 * <ul>
 *   <li>Flow hanya menyimpan <b>referensi</b> nama secret, bukan nilainya.</li>
 *   <li>API tidak pernah mengembalikan nilai secret; hanya petunjuk masked.</li>
 *   <li>Nilai disimpan terenkripsi AES-GCM untuk mode lokal.</li>
 * </ul>
 *
 * <p>Kunci diturunkan dari konfigurasi (SHA-256 dari passphrase). Untuk
 * produksi, kunci harus berasal dari KMS/Vault, bukan konfigurasi aplikasi.
 */
@Service
public class SecretService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretRefRepository repository;
    private final AuditService audit;
    private final JsonStore json;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public SecretService(SecretRefRepository repository,
                         AuditService audit,
                         JsonStore json,
                         @Value("${apistudio.secrets.local-key:dev-only-change-me}") String localKey) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
        this.key = deriveKey(localKey);
    }

    /** Daftar secret tanpa nilai (hanya petunjuk). */
    public List<SecretRef> list(String environment) {
        return environment == null || environment.isBlank()
                ? repository.findAllByOrderByNameAsc()
                : repository.findByEnvironmentOrderByNameAsc(environment);
    }

    /** Simpan atau perbarui nilai secret. */
    @Transactional
    public SecretRef put(String name, String environment, String value, String description, CurrentUser actor) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama secret wajib diisi");
        }
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nilai secret wajib diisi");
        }
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        SecretRef ref = repository.findByNameAndEnvironment(name.trim(), env)
                .orElseGet(() -> new SecretRef(name.trim(), env, description, actor.username()));
        ref.encryptedValue = encrypt(value);
        ref.hint = "****" + value.substring(Math.max(0, value.length() - 4));
        if (description != null) {
            ref.description = description;
        }
        ref.updatedAt = Instant.now();
        ref.updatedBy = actor.username();
        SecretRef saved = repository.save(ref);

        // audit tanpa nilai secret
        audit.record(actor, "SECRET_WRITTEN", "SecretRef", saved.id,
                "Secret " + name + " diperbarui pada environment " + env);
        return saved;
    }

    @Transactional
    public void delete(String secretId, CurrentUser actor) {
        SecretRef ref = repository.findById(secretId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Secret tidak ditemukan"));
        repository.delete(ref);
        audit.record(actor, "SECRET_DELETED", "SecretRef", secretId,
                "Secret " + ref.name + " dihapus pada environment " + ref.environment);
    }

    /**
     * Resolver untuk engine: nama secret &rarr; nilai terdekripsi.
     *
     * <p>Engine tidak pernah menyimpan nilai; resolver hanya hidup selama run.
     */
    public SecretResolver resolverFor(String environment) {
        String env = environment == null || environment.isBlank() ? "dev" : environment;
        return name -> repository.findByNameAndEnvironment(name, env)
                .map(ref -> decrypt(ref.encryptedValue))
                .orElse(null);
    }

    /** Dekripsi nilai secret; {@code null} bila tidak ada. */
    public String valueOf(String name, String environment) {
        String env = environment == null || environment.isBlank() ? "dev" : environment;
        return repository.findByNameAndEnvironment(name, env)
                .map(ref -> decrypt(ref.encryptedValue))
                .orElse(null);
    }

    /** Bentuk view untuk API: tanpa nilai, hanya petunjuk dan metadata. */
    public Map<String, Object> view(SecretRef ref) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", ref.id);
        view.put("name", ref.name);
        view.put("environment", ref.environment);
        view.put("hint", ref.hint);
        view.put("description", ref.description);
        view.put("vaultPath", ref.vaultPath);
        view.put("managedExternally", ref.vaultPath != null && !ref.vaultPath.isBlank());
        view.put("createdAt", ref.createdAt);
        view.put("updatedAt", ref.updatedAt);
        view.put("updatedBy", ref.updatedBy);
        return view;
    }

    // ------------------------------------------------- kriptografi lokal

    /** Enkripsi AES-GCM; hasil = Base64(IV || ciphertext || tag). */
    String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal mengenkripsi secret: " + e.getMessage(), e);
        }
    }

    /** Dekripsi; mengembalikan {@code null} bila data rusak. */
    String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] deriveKey(String localKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(localKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Tidak dapat menurunkan kunci secret", e);
        }
    }

    /** Map nilai secret untuk environment (dipakai template yang butuh banyak secret). */
    public Map<String, String> snapshot(String environment) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SecretRef ref : list(environment)) {
            map.put(ref.name, decrypt(ref.encryptedValue));
        }
        return map;
    }
}
