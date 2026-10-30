package io.apistudio.contracts.flow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Konfigurasi penanganan error per node (BRD FR-FLOW-06).
 *
 * @param timeoutMs      batas waktu eksekusi node; {@code null} berarti tanpa timeout
 * @param retries        jumlah percobaan ulang; 0 berarti tanpa retry
 * @param backoffMs      jeda antar percobaan ulang
 * @param onError        perilaku setelah semua retry gagal
 * @param fallbackNodeId node tujuan ketika {@code onError = FALLBACK}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorPolicy(
        Long timeoutMs,
        Integer retries,
        Long backoffMs,
        OnError onError,
        String fallbackNodeId
) {

    /** Perilaku saat node gagal. */
    public enum OnError {
        /** Gagalkan flow (default). */
        FAIL,
        /** Lanjutkan ke edge berikutnya walau node gagal; error tetap dicatat. */
        CONTINUE,
        /** Lompat ke {@link #fallbackNodeId}. */
        FALLBACK
    }

    public static ErrorPolicy defaults() {
        return new ErrorPolicy(null, 0, 0L, OnError.FAIL, null);
    }

    public int retriesOrZero() {
        return retries == null ? 0 : Math.max(0, retries);
    }

    public long backoffOrZero() {
        return backoffMs == null ? 0L : Math.max(0L, backoffMs);
    }

    public OnError onErrorOrDefault() {
        return onError == null ? OnError.FAIL : onError;
    }

    /** Salinan dengan nilai yang diberikan; {@code null} mempertahankan nilai lama. */
    public ErrorPolicy merge(Long timeoutMs, Integer retries, Long backoffMs, OnError onError, String fallbackNodeId) {
        return new ErrorPolicy(
                timeoutMs == null ? this.timeoutMs : timeoutMs,
                retries == null ? this.retries : retries,
                backoffMs == null ? this.backoffMs : backoffMs,
                onError == null ? this.onError : onError,
                fallbackNodeId == null ? this.fallbackNodeId : fallbackNodeId
        );
    }
}
