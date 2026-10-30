package io.apistudio.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Hasil satu eksekusi test case (BRD FR-SIM-09).
 *
 * <p>Disimpan agar riwayat dapat dilihat dan menjadi bukti gate publish.
 */
@Entity
@Table(name = "test_run", indexes = {
        @Index(name = "idx_testrun_api", columnList = "apiId"),
        @Index(name = "idx_testrun_case", columnList = "testCaseId")
})
public class TestRun {

    /** Status hasil run. */
    public enum Status {
        /** Semua assertion lulus. */
        PASSED,
        /** Ada assertion gagal. */
        FAILED,
        /** Flow gagal dieksekusi (error engine). */
        ERROR
    }

    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    public String apiId;

    public String versionId;

    public String testCaseId;

    public String testCaseName;

    @Enumerated(EnumType.STRING)
    public Status status = Status.PASSED;

    public String environment = "dev";

    public int statusCode;

    public long durationMs;

    @Column(length = 100000)
    public String responseJson = "{}";

    @Column(length = 100000)
    public String traceJson = "[]";

    @Column(length = 20000)
    public String assertionsJson = "[]";

    public int passedAssertions;

    public int failedAssertions;

    @Column(length = 4000)
    public String errorMessage;

    public String traceId;

    public Instant createdAt = Instant.now();

    public String createdBy;

    public TestRun() {
    }

    public boolean passed() {
        return status == Status.PASSED;
    }
}
