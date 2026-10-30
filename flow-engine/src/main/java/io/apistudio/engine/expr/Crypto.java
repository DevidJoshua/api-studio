package io.apistudio.engine.expr;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Operasi kriptografi yang dipakai ekspresi dan function template. */
public final class Crypto {

    private static final HexFormat HEX = HexFormat.of();

    private Crypto() {
    }

    /** SHA-256 dalam hex huruf kecil. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ExpressionException("SHA-256 tidak tersedia", e);
        }
    }

    /** HMAC-SHA256 dalam hex huruf kecil. */
    public static String hmacSha256Hex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ExpressionException("HMAC-SHA256 gagal: " + e.getMessage(), e);
        }
    }

    /** SHA-256 dalam Base64 (dipakai sebagian skema signature). */
    public static String sha256Base64(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getEncoder()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ExpressionException("SHA-256 tidak tersedia", e);
        }
    }
}
