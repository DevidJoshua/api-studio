package io.apistudio.engine.validation;

/**
 * Satu temuan validasi flow (BRD FR-FLOW-04).
 *
 * @param severity tingkat keparahan
 * @param code     kode stabil untuk ditampilkan/diuji
 * @param message  pesan yang dapat dibaca pengguna
 * @param nodeId   node terkait, bila ada
 */
public record ValidationIssue(
        Severity severity,
        String code,
        String message,
        String nodeId
) {

    public enum Severity {
        /** Menghalangi publish. */
        ERROR,
        /** Perlu diperhatikan; tidak menghalangi. */
        WARNING
    }

    public static ValidationIssue error(String code, String message, String nodeId) {
        return new ValidationIssue(Severity.ERROR, code, message, nodeId);
    }

    public static ValidationIssue warning(String code, String message, String nodeId) {
        return new ValidationIssue(Severity.WARNING, code, message, nodeId);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
