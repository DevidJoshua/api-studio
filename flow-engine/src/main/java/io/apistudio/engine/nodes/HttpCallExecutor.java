package io.apistudio.engine.nodes;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.expr.ExpressionEngine;
import io.apistudio.engine.expr.Json;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node HTTP_CALL: memanggil dependensi eksternal, mendukung mock (BRD FR-SIM-04).
 *
 * <p>Konfigurasi:
 * <pre>
 * {
 *   "url": "https://api.partner.com/transfer",
 *   "method": "POST",
 *   "headers": { "Authorization": "Bearer ${secrets.partnerToken}" },
 *   "body": { "amount": "=request.body.amount" },
 *   "timeoutMs": 5000
 * }
 * </pre>
 *
 * <p>Mode mock: {@code MOCK} (default bila ada mock), {@code REAL},
 * {@code ERROR_TIMEOUT}, {@code ERROR_5XX}.
 */
public final class HttpCallExecutor extends AbstractNodeExecutor {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) throws Exception {
        String url = cfgText(node, "url", ctx, "");
        if (url.isBlank()) {
            throw new IllegalArgumentException("URL wajib pada node HTTP_CALL " + node.id());
        }
        String method = cfgText(node, "method", ctx, "GET").toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> rawHeaders = cfgMap(node, "headers", ctx);
        Map<String, String> headers = new LinkedHashMap<>();
        rawHeaders.forEach((k, v) -> headers.put(k, ExpressionEngine.stringify(v)));

        Object body = cfgValue(node, "body", ctx);
        String requestBody = body == null ? null : Json.encode(body);

        ExecutionRequest.HttpMock mock = ctx.mocks().find(url);
        if (mock != null) {
            return fromMock(node, ctx, mock, url);
        }

        long started = System.currentTimeMillis();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1, cfgNumber(node, "timeoutMs", ctx, 10_000))));
        headers.forEach(builder::header);
        if (requestBody != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(requestBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        ctx.log("Memanggil " + method + " " + url);
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        long duration = System.currentTimeMillis() - started;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", response.statusCode());
        output.put("headers", response.headers().map());
        output.put("body", parseBody(response.body()));
        output.put("durationMs", duration);
        ctx.setVar("httpResult", output);
        ctx.log("Response HTTP " + response.statusCode() + " dalam " + duration + " ms");
        return NodeResult.out(output);
    }

    /** Hasilkan output dari konfigurasi mock tanpa memanggil jaringan. */
    private NodeResult fromMock(FlowNode node, FlowRuntimeContext ctx,
                                ExecutionRequest.HttpMock mock, String url) {
        long delay = mock.delayMs() == null ? 0L : Math.max(0L, mock.delayMs());
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int status;
        Object body;
        switch (mock.mode()) {
            case ERROR_TIMEOUT -> throw new IllegalStateException(
                    "Mock timeout untuk " + url + " (node " + node.id() + ")");
            case ERROR_5XX -> {
                status = 502;
                body = mock.body() == null
                        ? Map.of("error", "MOCK_5XX", "message", "Kegagalan simulasi dari mock")
                        : mock.body();
            }
            case REAL -> throw new IllegalStateException(
                    "Mode REAL tidak didukung di dalam mock: " + url);
            default -> {
                status = mock.status() == null ? 200 : mock.status();
                body = mock.body() == null ? Map.of() : mock.body();
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("headers", mock.headers());
        output.put("body", body);
        output.put("durationMs", delay);
        output.put("mocked", true);
        ctx.setVar("httpResult", output);
        ctx.log("Mock HTTP " + status + " untuk " + url);
        return NodeResult.out(output);
    }

    private static Object parseBody(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Object parsed = Json.decode(text, null);
        return parsed == null ? text : parsed;
    }
}
