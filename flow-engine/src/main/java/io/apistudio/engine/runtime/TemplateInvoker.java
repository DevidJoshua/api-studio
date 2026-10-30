package io.apistudio.engine.runtime;

import java.util.Map;

/**
 * Pemanggil function template (BRD FR-TPL-01..07).
 *
 * <p>Engine tidak tahu isi template; Control Plane menyediakan implementasi
 * yang mengeksekusi template composite, kode Java, atau ekspresi script.
 */
@FunctionalInterface
public interface TemplateInvoker {

    /** Pemanggil kosong: setiap pemanggilan gagal dengan pesan jelas. */
    TemplateInvoker NONE = (key, args, ctx) -> {
        throw new IllegalStateException("Function template tidak tersedia pada run ini: " + key);
    };

    /**
     * Panggil template.
     *
     * @param templateKey kunci template beserta versi, mis. {@code snapbi.signature@1}
     * @param args        argumen yang sudah dievaluasi
     * @param ctx         konteks run (boleh null pada pemakaian di luar flow)
     * @return hasil template
     */
    Object invoke(String templateKey, Map<String, Object> args, FlowRuntimeContext ctx);
}
