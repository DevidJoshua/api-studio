package io.apistudio.cp.security;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Peran pengguna dan haknya (BRD Bagian 4, FR-SEC-01).
 *
 * <p>Matriks kewenangan dipusatkan di sini agar mudah diaudit: satu tempat
 * untuk menjawab "peran apa boleh melakukan apa".
 *
 * <ul>
 *   <li>ADMIN — seluruh izin.</li>
 *   <li>DESIGNER — menulis API, flow, kode Java, test; mengajukan publish; deploy.</li>
 *   <li>REVIEWER — membaca, menjalankan test, menyetujui publish.</li>
 *   <li>QA — membaca, menulis dan menjalankan test (tidak mengubah API).</li>
 *   <li>OPERATOR — deploy, rollback, environment, observability.</li>
 *   <li>AUDITOR — hanya membaca, audit, dan observability.</li>
 * </ul>
 */
public enum Role {

    ADMIN("Administrator Platform", Set.of(Permission.values())),

    DESIGNER("API Designer / Developer", Set.of(
            Permission.API_READ, Permission.API_WRITE,
            Permission.FLOW_READ, Permission.FLOW_WRITE,
            Permission.TEMPLATE_READ, Permission.TEMPLATE_WRITE,
            Permission.JAVA_READ, Permission.JAVA_WRITE,
            Permission.TEST_READ, Permission.TEST_WRITE, Permission.TEST_RUN,
            Permission.PUBLISH_REQUEST,
            Permission.DEPLOY,
            Permission.SECRET_READ_REFERENCE,
            Permission.AUDIT_READ,
            Permission.OBSERVE
    )),

    REVIEWER("Reviewer / Approver", Set.of(
            Permission.API_READ, Permission.FLOW_READ,
            Permission.TEMPLATE_READ, Permission.JAVA_READ,
            Permission.TEST_READ, Permission.TEST_RUN,
            Permission.PUBLISH_APPROVE,
            Permission.AUDIT_READ,
            Permission.OBSERVE
    )),

    QA("QA / Tester", Set.of(
            Permission.API_READ, Permission.FLOW_READ,
            Permission.TEMPLATE_READ, Permission.JAVA_READ,
            Permission.TEST_READ, Permission.TEST_WRITE, Permission.TEST_RUN,
            Permission.AUDIT_READ,
            Permission.OBSERVE
    )),

    OPERATOR("Operator / DevOps", Set.of(
            Permission.API_READ, Permission.FLOW_READ,
            Permission.DEPLOY, Permission.ROLLBACK,
            Permission.ENVIRONMENT_WRITE,
            Permission.OBSERVE, Permission.AUDIT_READ
    )),

    AUDITOR("Auditor", Set.of(
            Permission.API_READ, Permission.FLOW_READ,
            Permission.AUDIT_READ, Permission.OBSERVE
    ));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public String label() {
        return label;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** Parse nama peran; tidak dikenal &rarr; DESIGNER (hak paling terbatas untuk menulis). */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            return DESIGNER;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DESIGNER;
        }
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
