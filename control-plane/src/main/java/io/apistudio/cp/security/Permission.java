package io.apistudio.cp.security;

/**
 * Daftar hak akses granular (BRD Bagian 4).
 *
 * <p>Hak akses dipisah dari peran agar matriks kewenangan dapat diaudit dan
 * diuji tanpa menyentuh logika bisnis.
 */
public enum Permission {
    API_READ,
    API_WRITE,
    FLOW_READ,
    FLOW_WRITE,
    TEMPLATE_READ,
    TEMPLATE_WRITE,
    JAVA_READ,
    JAVA_WRITE,
    TEST_READ,
    TEST_WRITE,
    TEST_RUN,
    PUBLISH_REQUEST,
    PUBLISH_APPROVE,
    DEPLOY,
    ROLLBACK,
    ENVIRONMENT_WRITE,
    SECRET_READ_REFERENCE,
    SECRET_WRITE,
    USER_MANAGE,
    AUDIT_READ,
    OBSERVE
}
