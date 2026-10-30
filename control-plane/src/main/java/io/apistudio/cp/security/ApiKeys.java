package io.apistudio.cp.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Pembuatan dan verifikasi API key consumer (BRD FR-API-05).
 *
 * <p>Nilai API key hanya ditampilkan sekali saat dibuat; yang disimpan adalah
 * hash SHA-256. Hashing dipusatkan di sini agar pembuatan (AdminController)
 * dan verifikasi (GatewayService) selalu memakai algoritme yang sama.
 *
 * <p>API key dibuat acak 128-bit sehingga SHA-256 tanpa salt sudah memadai:
 * tidak ada ruang bagi penyerang untuk menebak, dan verifikasi harus cepat di
 * jalur data plane.
 */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final String PREFIX = "key_";

    private ApiKeys() {
    }

    /** Buat API key baru berformat {@code key_<32 hex>}. */
    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return PREFIX + HEX.formatHex(bytes);
    }

    /** Hash API key untuk disimpan. */
    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e);
        }
    }

    /** Petunjuk masked untuk ditampilkan di UI: {@code ****abcd}. */
    public static String hint(String apiKey) {
        if (apiKey == null || apiKey.length() < 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Normalisasi kredensial: buang skema {@code Bearer} bila ada.
     *
     * <p>Klien boleh mengirim {@code X-API-Key} polos atau
     * {@code Authorization: Bearer <key>}.
     */
    public static String normalize(String credential) {
        if (credential == null) {
            return null;
        }
        String trimmed = credential.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    /** Verifikasi kredensial terhadap hash tersimpan (perbandingan waktu konstan). */
    public static boolean matches(String credential, String storedHash) {
        if (credential == null || storedHash == null) {
            return false;
        }
        String candidate = hash(credential);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
