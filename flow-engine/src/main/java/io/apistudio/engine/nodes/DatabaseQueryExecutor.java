package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;
import io.apistudio.engine.runtime.SandboxDatabase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node DATABASE_QUERY: query read-only ke database sandbox (BRD FR-SIM-05/07).
 *
 * <pre>
 * { "sql": "select * from account where account_no = ?", "params": ["=request.body.accountNo"] }
 * </pre>
 *
 * <p>SQL literal tidak dievaluasi sebagai ekspresi (spasi dan kurung akan
 * memicu galat sintaks). Ekspresi hanya dipakai bila string diawali {@code =}
 * atau memakai template {@code ${...}}.
 */
public final class DatabaseQueryExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        SandboxDatabase db = ctx.sandbox();
        if (db == null || !db.enabled()) {
            throw new IllegalStateException("Database sandbox tidak aktif pada run ini");
        }
        String sql = node.cfgString("sql", "");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL wajib pada node DATABASE_QUERY " + node.id());
        }
        sql = resolveSql(sql, ctx);

        Map<String, Object> params = resolveParams(node, ctx);
        SandboxDatabase.QueryResult result = db.query(sql, params);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("columns", result.columns());
        output.put("rows", result.rows());
        output.put("rowCount", (long) result.rowCount());
        output.put("durationMs", result.durationMs());

        ctx.setVar("dbResult", output);
        log(ctx, "Query mengembalikan " + result.rowCount() + " baris");
        return NodeResult.out(output);
    }

    /** Parameter dapat berupa list (posisional) atau map (bernama). */
    private Map<String, Object> resolveParams(FlowNode node, FlowRuntimeContext ctx) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object raw = node.cfg("params");
        if (raw instanceof List<?> list) {
            int index = 1;
            for (Object item : list) {
                params.put("p" + index++, ctx.valueOf(item));
            }
        } else if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> params.put(String.valueOf(k), ctx.valueOf(v)));
        }
        return params;
    }

    private static String resolveSql(String raw, FlowRuntimeContext ctx) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("=")) {
            Object value = ctx.eval(trimmed.substring(1));
            return value == null ? null : String.valueOf(value);
        }
        if (trimmed.contains("${")) {
            return ctx.expression().interpolate(trimmed);
        }
        return trimmed;
    }
}
