package io.apistudio.engine.runtime;

import io.apistudio.contracts.exec.TraceStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Perekam jejak eksekusi per node (BRD FR-SIM-02/03).
 *
 * <p>Jumlah langkah dibatasi agar flow yang salah tidak menghabiskan memori.
 * Nilai input/output dimask sebelum disimpan agar data sensitif tidak bocor
 * ke trace.
 */
public final class TraceRecorder {

    /** Batas jumlah langkah yang direkam. */
    public static final int DEFAULT_MAX_STEPS = 5000;

    private final List<TraceStep> steps = new ArrayList<>();
    private final int maxSteps;
    private boolean truncated;

    public TraceRecorder() {
        this(DEFAULT_MAX_STEPS);
    }

    public TraceRecorder(int maxSteps) {
        this.maxSteps = maxSteps <= 0 ? DEFAULT_MAX_STEPS : maxSteps;
    }

    /** True bila perekaman dipotong karena melebihi batas. */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Catat satu langkah.
     *
     * @return langkah yang tercatat, atau {@code null} bila batas tercapai
     */
    public TraceStep record(String nodeId, String nodeType, String label, TraceStep.Status status,
                            String port, Object input, Object output, long durationMs,
                            String error, List<String> logs, int attempt) {
        if (steps.size() >= maxSteps) {
            truncated = true;
            return null;
        }
        TraceStep step = new TraceStep(nodeId, nodeType, label, status, port,
                Masking.mask(input), Masking.mask(output), durationMs,
                error == null ? null : Masking.mask(error).toString(),
                logs, attempt);
        steps.add(step);
        return step;
    }

    /** Seluruh langkah yang terekam. */
    public List<TraceStep> steps() {
        return List.copyOf(steps);
    }

    /** True bila node tertentu pernah dieksekusi dengan status OK. */
    public boolean executed(String nodeId) {
        return steps.stream().anyMatch(s -> s.nodeId().equals(nodeId) && s.status() == TraceStep.Status.OK);
    }

    /** True bila node tertentu sama sekali tidak dieksekusi. */
    public boolean skipped(String nodeId) {
        return steps.stream().noneMatch(s -> s.nodeId().equals(nodeId));
    }
}
