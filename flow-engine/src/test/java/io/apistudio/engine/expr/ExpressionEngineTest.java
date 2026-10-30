package io.apistudio.engine.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian interpreter ekspresi (BRD FR-FLOW-07). */
class ExpressionEngineTest {

    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final ExpressionEngine engine = new ExpressionEngine(
            vars::get,
            () -> "2026-10-29T10:00:00+07:00",
            "secret-key");

    private void givenRequestBody(Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "POST");
        request.put("body", body);
        vars.put("request", request);
    }

    @Test
    @DisplayName("akses path bertitik ke dalam body")
    void pathAccess() {
        givenRequestBody(Map.of("amount", Map.of("value", "15000.00"), "id", "A1"));
        assertEquals("A1", engine.eval("request.body.id"));
        assertEquals("15000.00", engine.eval("request.body.amount.value"));
        assertNull(engine.eval("request.body.tidakAda"));
    }

    @Test
    @DisplayName("indeks list")
    void listIndex() {
        givenRequestBody(Map.of("items", List.of("a", "b", "c")));
        assertEquals("b", engine.eval("request.body.items[1]"));
        assertEquals(3, engine.eval("request.body.items.length"));
    }

    @Test
    @DisplayName("template string menggabungkan ekspresi dan teks")
    void interpolation() {
        givenRequestBody(Map.of("id", "ABC", "n", 5));
        assertEquals("ID: ABC (5)", engine.interpolate("ID: ${request.body.id} (${request.body.n})"));
    }

    @Test
    @DisplayName("resolve mempertahankan tipe asli untuk ekspresi penuh")
    void resolveKeepsType() {
        givenRequestBody(Map.of("amount", 1500));
        Object value = engine.resolve("=request.body.amount");
        assertEquals(1500, value);
        assertTrue(value instanceof Integer, "harus tetap Integer, bukan String");
    }

    @Test
    @DisplayName("aritmetika presisi bilangan bulat")
    void arithmetic() {
        givenRequestBody(Map.of("price", 2500, "qty", 4));
        assertEquals(10000, engine.eval("request.body.price * request.body.qty"));
        assertEquals(2504, engine.eval("request.body.price + request.body.qty"));
        assertEquals(2496, engine.eval("request.body.price - request.body.qty"));
        assertEquals(625, engine.eval("request.body.price / request.body.qty"));
    }

    @Test
    @DisplayName("operasi pembagian desimal tidak dibulatkan")
    void decimalDivision() {
        givenRequestBody(Map.of("a", 10, "b", 4));
        assertEquals(2.5, engine.eval("request.body.a / request.body.b"));
    }

    @Test
    @DisplayName("perbandingan dan logika")
    void comparisons() {
        givenRequestBody(Map.of("amount", 1500, "status", "ACTIVE"));
        assertEquals(true, engine.eval("request.body.amount > 1000"));
        assertEquals(false, engine.eval("request.body.amount < 1000"));
        assertEquals(true, engine.eval("request.body.amount >= 1500 && request.body.status == 'ACTIVE'"));
        assertEquals(true, engine.eval("request.body.amount < 100 || request.body.status == 'ACTIVE'"));
        assertEquals(false, engine.eval("!true"));
    }

    @Test
    @DisplayName("ternary")
    void ternary() {
        givenRequestBody(Map.of("amount", 500));
        assertEquals("kecil", engine.eval("request.body.amount > 1000 ? 'besar' : 'kecil'"));
    }

    @Test
    @DisplayName("perbandingan angka berbentuk string tetap numerik")
    void numericStringComparison() {
        givenRequestBody(Map.of("amount", "15000.00"));
        assertEquals(true, engine.eval("number(request.body.amount) > 1000"));
    }

    @Test
    @DisplayName("fungsi string")
    void stringFunctions() {
        givenRequestBody(Map.of("name", "  budi  "));
        assertEquals("BUDI", engine.eval("upper(trim(request.body.name))"));
        assertEquals("budi", engine.eval("lower(trim(request.body.name))"));
        assertEquals(4, engine.eval("length(trim(request.body.name))"));
    }

    @Test
    @DisplayName("SHA-256 sesuai nilai acuan")
    void sha256() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                engine.eval("sha256('hello')"));
    }

    @Test
    @DisplayName("HMAC-SHA256 sesuai nilai acuan RFC 4231")
    void hmacSha256() {
        // kasus uji 2 RFC 4231: key "Jefe", data "what do ya want for nothing?"
        ExpressionEngine hmac = new ExpressionEngine(vars::get, () -> "", "Jefe");
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                hmac.eval("hmacSha256('what do ya want for nothing?')"));
    }

    @Test
    @DisplayName("timestamp deterministik dari Clock")
    void deterministicTimestamp() {
        assertEquals("2026-10-29T10:00:00+07:00", engine.eval("timestamp()"));
        assertEquals("2026-10-29T10:00:00+07:00", engine.eval("now()"));
    }

    @Test
    @DisplayName("Clock dipakai untuk timestamp pada engine")
    void clockBackedTimestamp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        ExpressionEngine withClock = new ExpressionEngine(
                vars::get,
                () -> java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                        .withZone(ZoneOffset.UTC).format(fixed.instant()),
                null);
        assertEquals("2026-01-02T03:04:05Z", withClock.eval("timestamp()"));
    }

    @Test
    @DisplayName("ekspresi tidak valid melempar ExpressionException")
    void invalidExpression() {
        assertThrows(ExpressionException.class, () -> engine.eval("request.body."));
        assertThrows(ExpressionException.class, () -> engine.eval("1 +"));
        assertThrows(ExpressionException.class, () -> engine.eval("unknownFn(1)"));
    }

    @Test
    @DisplayName("pembagian dengan nol ditolak jelas")
    void divisionByZero() {
        ExpressionException error = assertThrows(ExpressionException.class, () -> engine.eval("10 / 0"));
        assertTrue(error.getMessage().contains("nol"));
    }

    @Test
    @DisplayName("coalesce mengambil nilai pertama yang tidak null")
    void coalesce() {
        givenRequestBody(Map.of("a", "isi"));
        assertEquals("isi", engine.eval("coalesce(request.body.a, request.body.b, 'default')"));
        assertEquals("default", engine.eval("coalesce(request.body.b, 'default')"));
    }

    @Test
    @DisplayName("json() menyerialkan struktur")
    void jsonFunction() {
        givenRequestBody(Map.of("obj", Map.of("k", 1)));
        assertEquals("{\"k\":1}", engine.eval("json(request.body.obj)"));
    }

    @Test
    @DisplayName("string tanpa ekspresi dikembalikan apa adanya")
    void plainStringPassthrough() {
        assertEquals("teks biasa", engine.resolve("teks biasa"));
        assertFalse(engine.stringify(null).contains("null"));
    }

    @Test
    @DisplayName("resolve rekursif pada map dan list")
    void recursiveResolve() {
        givenRequestBody(Map.of("id", "X1"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "=request.body.id");
        input.put("nested", Map.of("label", "id-${request.body.id}"));
        input.put("list", List.of("=request.body.id", "tetap"));

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) engine.resolve(input);
        assertEquals("X1", resolved.get("id"));
        assertEquals("id-X1", ((Map<?, ?>) resolved.get("nested")).get("label"));
        assertEquals(List.of("X1", "tetap"), resolved.get("list"));
    }
}
