package io.apistudio.cp.service;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.cp.domain.FunctionTemplate;
import io.apistudio.cp.domain.TemplateVersion;
import io.apistudio.engine.expr.ExpressionEngine;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.TemplateInvoker;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Eksekusi function template saat dipanggil node TEMPLATE_CALL
 * (BRD FR-TPL-02/06).
 *
 * <p>Implementasi tiap jenis:
 * <ul>
 *   <li><b>SCRIPT</b> &mdash; ekspresi dievaluasi dengan {@code inputs.*} terikat
 *       ke argumen pemanggil (katalog bawaan memakai jenis ini).</li>
 *   <li><b>COMPOSITE</b> &mdash; sub-flow dijalankan pada engine yang sama dengan
 *       variabel {@code inputs} terisi, lalu hasilnya diambil dari variabel
 *       {@code templateResult}.</li>
 *   <li><b>JAVA</b> &mdash; ditolak dengan pesan jelas; kompilasi template Java
 *       terpisah dari node Java dan belum diaktifkan pada MVP.</li>
 * </ul>
 *
 * <p>Pemanggilan template dari dalam template lain dideteksi siklusnya
 * (FR-TPL-06) agar stack tidak meledak.
 */
@Service
public class TemplateInvokerService {

    /** Batas kedalaman pemanggilan template bersarang (FR-TPL-06). */
    private static final int MAX_DEPTH = 8;

    private final TemplateService templateService;
    private final JsonStore json;

    public TemplateInvokerService(TemplateService templateService, JsonStore json) {
        this.templateService = templateService;
        this.json = json;
    }

    /**
     * Buat invoker untuk satu run.
     *
     * @param ctx        konteks flow pemanggil
     * @param subFlowRun cara menjalankan sub-flow (disediakan engine/Control Plane
     *                   agar tidak terjadi ketergantungan melingkar)
     */
    public TemplateInvoker invokerFor(FlowRuntimeContext ctx, SubFlowRunner subFlowRun) {
        return new RunInvoker(ctx, subFlowRun, new LinkedHashSet<>(), 0);
    }

    /** Menjalankan sub-flow untuk template COMPOSITE. */
    @FunctionalInterface
    public interface SubFlowRunner {
        /**
         * @param flow definisi sub-flow template
         * @param ctx  konteks run induk (variabel sudah diisi {@code inputs})
         * @return hasil eksekusi sub-flow
         */
        Object run(FlowDefinition flow, FlowRuntimeContext ctx);
    }

    /** Invoker per run dengan pelacakan stack pemanggilan. */
    private final class RunInvoker implements TemplateInvoker {

        private final FlowRuntimeContext ctx;
        private final SubFlowRunner subFlowRun;
        private final LinkedHashSet<String> stack;
        private final int depth;

        RunInvoker(FlowRuntimeContext ctx, SubFlowRunner subFlowRun,
                   LinkedHashSet<String> stack, int depth) {
            this.ctx = ctx;
            this.subFlowRun = subFlowRun;
            this.stack = stack;
            this.depth = depth;
        }

        @Override
        public Object invoke(String templateKey, Map<String, Object> args, FlowRuntimeContext unusedCtx) {
            return invokeInternal(templateKey, args == null ? Map.of() : args);
        }

        private Object invokeInternal(String templateKey, Map<String, Object> args) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalStateException(
                        "Kedalaman pemanggilan template melebihi " + MAX_DEPTH + ": " + templateKey);
            }
            TemplateVersion version = resolveVersion(templateKey);
            String qualified = version.qualifiedKey();
            if (!stack.add(qualified)) {
                throw new IllegalStateException("Siklus template terdeteksi: "
                        + String.join(" -> ", stack) + " -> " + qualified);
            }
            try {
                return switch (version.implType) {
                    case SCRIPT -> evalScript(version, args);
                    case COMPOSITE -> evalComposite(version, args);
                    case JAVA -> throw new IllegalStateException(
                            "Template Java belum diaktifkan pada MVP: " + qualified
                                    + ". Gunakan template SCRIPT atau COMPOSITE.");
                };
            } finally {
                stack.remove(qualified);
            }
        }

        /** Resolusi {@code key} atau {@code key@versi}. */
        private TemplateVersion resolveVersion(String templateKey) {
            if (templateKey == null || templateKey.isBlank()) {
                throw new IllegalArgumentException("Kunci template kosong");
            }
            int at = templateKey.lastIndexOf('@');
            if (at > 0) {
                String key = templateKey.substring(0, at);
                try {
                    int version = Integer.parseInt(templateKey.substring(at + 1).trim());
                    return templateService.findVersion(key, version);
                } catch (NumberFormatException e) {
                    // bukan versi numerik; perlakukan sebagai kunci biasa
                }
            }
            return templateService.latestVersion(templateKey);
        }

        /** SCRIPT: evaluasi ekspresi dengan {@code inputs} terikat. */
        private Object evalScript(TemplateVersion version, Map<String, Object> args) {
            Map<String, Object> inputs = new LinkedHashMap<>(args);
            // argumen yang tidak diberikan diisi null agar ekspresi tetap dapat dievaluasi
            for (Map<String, Object> spec : json.readMapList(version.inputsJson)) {
                Object name = spec.get("name");
                if (name != null) {
                    inputs.putIfAbsent(String.valueOf(name), null);
                }
            }

            ExpressionEngine engine = ctx.expression();
            ctx.setVar("inputs", inputs);

            String implementation = version.implementation == null ? "" : version.implementation.trim();
            if (implementation.startsWith("=")) {
                return engine.resolve(implementation);
            }
            return engine.interpolate(implementation);
        }

        /** COMPOSITE: jalankan sub-flow template pada engine yang sama. */
        private Object evalComposite(TemplateVersion version, Map<String, Object> args) {
            FlowDefinition flow = json.read(version.implementation, FlowDefinition.class, null);
            if (flow == null || flow.nodes().isEmpty()) {
                throw new IllegalStateException("Template composite tidak memiliki flow: "
                        + version.qualifiedKey());
            }
            if (subFlowRun == null) {
                throw new IllegalStateException("Sub-flow runner tidak tersedia untuk template "
                        + version.qualifiedKey());
            }

            Map<String, Object> inputs = new LinkedHashMap<>(args);
            ctx.setVar("inputs", inputs);
            ctx.setVar("templateName", version.templateKey);
            ctx.log("Menjalankan template composite " + version.qualifiedKey());

            return subFlowRun.run(flow, ctx);
        }
    }

    /** True bila jenis implementasi dapat dieksekusi pada MVP. */
    public static boolean supports(FunctionTemplate.ImplType implType) {
        return implType == FunctionTemplate.ImplType.SCRIPT
                || implType == FunctionTemplate.ImplType.COMPOSITE;
    }
}
