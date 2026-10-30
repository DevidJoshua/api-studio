package io.apistudio.cp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pengujian alur end-to-end Control Plane (BRD UC-01..UC-06).
 *
 * <p>Mencakup autentikasi, RBAC, manajemen API, penyimpanan flow, validasi,
 * kompilasi, simulasi, test suite, preflight, publish, deploy, gateway, dan
 * rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlPlaneFlowTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();
    private String designerToken;
    private String reviewerToken;
    private String operatorToken;
    private String adminToken;
    private String qaToken;

    @BeforeEach
    void login() throws Exception {
        designerToken = loginAs("designer", "password123");
        reviewerToken = loginAs("reviewer", "password123");
        operatorToken = loginAs("operator", "password123");
        adminToken = loginAs("admin", "admin123");
        qaToken = loginAs("qa", "password123");
    }

    // ------------------------------------------------------------ helper

    private String loginAs(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = readMap(result);
        assertNotNull(body.get("token"));
        return String.valueOf(body.get("token"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), List.class);
    }

    /** Buat API uji dengan path unik agar tidak bentrok antar test. */
    private Map<String, Object> createTestApi(String suffix) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "API Uji " + suffix);
        payload.put("path", "/uji/" + suffix);
        payload.put("method", "POST");
        payload.put("description", "API untuk pengujian otomatis");
        payload.put("tags", "uji");
        payload.put("policyJson", Map.of("auth", "none"));

        MvcResult result = mvc.perform(post("/api/apis")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readMap(result);
    }

    /** Flow sederhana: trigger -> kondisi -> dua response. */
    private static Map<String, Object> simpleFlow() {
        Map<String, Object> trigger = Map.of(
                "id", "trigger", "type", "TRIGGER", "label", "Request Masuk",
                "config", Map.of(), "position", Map.of("x", 80, "y", 200));

        Map<String, Object> condition = Map.of(
                "id", "cond", "type", "CONDITION", "label", "Nominal > 0?",
                "config", Map.of("expression", "=number(request.body.amount) > 0"),
                "position", Map.of("x", 300, "y", 200));

        Map<String, Object> okResponse = Map.of(
                "id", "resp_ok", "type", "RESPONSE", "label", "Response Sukses",
                "config", Map.of("status", 200, "body", Map.of(
                        "responseCode", "2007300",
                        "amount", "=request.body.amount")),
                "position", Map.of("x", 560, "y", 140));

        Map<String, Object> badResponse = Map.of(
                "id", "resp_bad", "type", "RESPONSE", "label", "Response Gagal",
                "config", Map.of("status", 400, "body", Map.of(
                        "responseCode", "4007302",
                        "responseMessage", "Invalid Amount")),
                "position", Map.of("x", 560, "y", 280));

        return Map.of(
                "name", "Flow Uji",
                "nodes", List.of(trigger, condition, okResponse, badResponse),
                "edges", List.of(
                        Map.of("id", "e1", "from", "trigger", "to", "cond", "fromPort", "out"),
                        Map.of("id", "e2", "from", "cond", "to", "resp_ok", "fromPort", "true"),
                        Map.of("id", "e3", "from", "cond", "to", "resp_bad", "fromPort", "false")));
    }

    // -------------------------------------------------------- autentikasi

    @Test
    @DisplayName("login gagal dengan password salah")
    void loginWithWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "designer", "password", "salah"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("endpoint terlindungi menolak tanpa token")
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/apis")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login menyertakan daftar izin agar UI dapat menegakkan RBAC")
    void loginIncludesPermissions() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "reviewer", "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = readMap(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) body.get("user");
        assertNotNull(user, "respons login harus memuat objek user");

        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) user.get("permissions");
        assertNotNull(permissions, "user.permissions wajib ada");
        assertFalse(permissions.isEmpty(), "daftar izin tidak boleh kosong");
        assertTrue(permissions.contains("PUBLISH_APPROVE"));
        assertFalse(permissions.contains("API_WRITE"));
    }
    @Test
    @DisplayName("profil mengembalikan peran dan izin")
    void profileReturnsPermissions() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> me = readMap(result);
        assertEquals("REVIEWER", me.get("role"));
        assertTrue(((List<?>) me.get("permissions")).contains("PUBLISH_APPROVE"));
        assertFalse(((List<?>) me.get("permissions")).contains("API_WRITE"));
    }

    // --------------------------------------------------------------- RBAC

    @Test
    @DisplayName("QA tidak boleh membuat API (403)")
    void qaCannotCreateApi() throws Exception {
        mvc.perform(post("/api/apis")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Dilarang", "path", "/dilarang", "method", "POST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AUDITOR tidak boleh deploy (403)")
    void auditorCannotDeploy() throws Exception {
        String auditorToken = loginAs("auditor", "password123");
        mvc.perform(post("/api/releases/apa-saja/deploy")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DESIGNER tidak boleh menyetujui publish (403)")
    void designerCannotApprove() throws Exception {
        mvc.perform(post("/api/approvals/apa-saja/decide")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("approve", true))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------ CRUD & versi

    @Test
    @DisplayName("membuat API menghasilkan versi draft dengan TRIGGER")
    void createApiCreatesDraft() throws Exception {
        Map<String, Object> api = createTestApi("buat-" + System.nanoTime());
        assertNotNull(api.get("id"));
        assertEquals(1, ((List<?>) api.get("versions")).size());
        assertNotNull(api.get("draftVersionId"));

        Map<String, Object> version = ((List<Map<String, Object>>) api.get("versions")).get(0);
        assertEquals("DRAFT", version.get("status"));
        assertEquals(1, version.get("versionNumber"));
    }

    @Test
    @DisplayName("path dan method duplikat ditolak (409)")
    void duplicatePathRejected() throws Exception {
        String suffix = "dup-" + System.nanoTime();
        createTestApi(suffix);

        mvc.perform(post("/api/apis")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Duplikat", "path", "/uji/" + suffix, "method", "POST"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("method tidak valid ditolak (400)")
    void invalidMethodRejected() throws Exception {
        mvc.perform(post("/api/apis")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Method Salah", "path", "/salah", "method", "TRACE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("flow tersimpan dan dapat dibaca kembali")
    void saveAndReadFlow() throws Exception {
        Map<String, Object> api = createTestApi("flow-" + System.nanoTime());
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/versions/" + versionId)
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> version = readMap(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> flow = (Map<String, Object>) version.get("flow");
        assertEquals(4, ((List<?>) flow.get("nodes")).size());
        assertEquals(3, ((List<?>) flow.get("edges")).size());
    }

    @Test
    @DisplayName("validasi menolak flow tanpa trigger")
    void validationRejectsFlowWithoutTrigger() throws Exception {
        Map<String, Object> api = createTestApi("val-" + System.nanoTime());
        String versionId = String.valueOf(api.get("draftVersionId"));

        Map<String, Object> badFlow = Map.of(
                "name", "Tanpa Trigger",
                "nodes", List.of(Map.of(
                        "id", "resp", "type", "RESPONSE", "label", "Response",
                        "config", Map.of("status", 200), "position", Map.of("x", 0, "y", 0))),
                "edges", List.of());

        MvcResult result = mvc.perform(post("/api/versions/" + versionId + "/validate")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", badFlow))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> report = readMap(result);
        assertEquals(false, report.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) report.get("issues");
        assertTrue(issues.stream().anyMatch(i -> "TRIGGER_MISSING".equals(i.get("code"))));
    }

    @Test
    @DisplayName("versi baru menyalin isi versi sebelumnya")
    void newVersionCopiesPrevious() throws Exception {
        Map<String, Object> api = createTestApi("versi-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        MvcResult created = mvc.perform(post("/api/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("changeNote", "versi kedua"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> newVersion = readMap(created);
        assertEquals(2, newVersion.get("versionNumber"));
        assertEquals(4, ((Map<?, ?>) newVersion.get("flowSummary")).get("nodes"));
    }

    // --------------------------------------------------------- simulasi

    @Test
    @DisplayName("simulasi menjalankan flow dan mengembalikan trace")
    void simulateReturnsTrace() throws Exception {
        Map<String, Object> api = createTestApi("sim-" + System.nanoTime());
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/simulate")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "versionId", versionId,
                                "method", "POST",
                                "path", api.get("path"),
                                "body", Map.of("amount", 15000)))))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> simulation = readMap(result);
        assertEquals(200, simulation.get("statusCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) simulation.get("body");
        assertEquals("2007300", body.get("responseCode"));
        assertFalse(((List<?>) simulation.get("trace")).isEmpty());
    }

    @Test
    @DisplayName("node Java dikompilasi lalu dieksekusi end-to-end lewat simulasi")
    void javaNodeCompilesAndRuns() throws Exception {
        Map<String, Object> api = createTestApi("java-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        // flow: trigger -> java -> response
        Map<String, Object> flow = Map.of(
                "name", "Flow Java",
                "nodes", List.of(
                        Map.of("id", "trigger", "type", "TRIGGER", "label", "Masuk",
                                "config", Map.of(), "position", Map.of("x", 0, "y", 0)),
                        Map.of("id", "java", "type", "JAVA_CODE", "label", "Hitung",
                                "config", Map.of("source", "return 1;", "libraries", List.of()),
                                "position", Map.of("x", 200, "y", 0)),
                        Map.of("id", "resp", "type", "RESPONSE", "label", "Sukses",
                                "config", Map.of("status", 200, "body", Map.of(
                                        "hasil", "=vars.javaResult",
                                        "id", "=request.body.id")),
                                "position", Map.of("x", 400, "y", 0))),
                "edges", List.of(
                        Map.of("id", "e1", "from", "trigger", "to", "java", "fromPort", "out"),
                        Map.of("id", "e2", "from", "java", "to", "resp", "fromPort", "out")));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", flow))))
                .andExpect(status().isOk());

        // kode yang membaca input dan menulis variabel flow
        Map<String, Object> sources = Map.of("java", Map.of(
                "source", """
                        String id = ctx.input("id") == null ? "-" : ctx.input("id").toString();
                        ctx.set("javaResult", id + "|ok");
                        return ctx.get("javaResult");
                        """,
                "libraries", List.of()));
        mvc.perform(put("/api/versions/" + versionId + "/java")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("javaSources", sources))))
                .andExpect(status().isOk());

        // kompilasi harus berhasil
        MvcResult compile = mvc.perform(post("/api/versions/" + versionId + "/compile")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> compileResult = readMap(compile);
        assertEquals(true, compileResult.get("success"),
                () -> "kompilasi harus berhasil: " + safeBody(compile));

        // simulasi harus mengeksekusi kode Java dan mengembalikan hasilnya
        MvcResult simulation = mvc.perform(post("/api/simulate")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "versionId", versionId,
                                "method", "POST",
                                "path", api.get("path"),
                                "body", Map.of("id", "ABC")))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> simResult = readMap(simulation);
        assertEquals(200, simResult.get("statusCode"),
                () -> "simulasi harus sukses: " + safeBody(simulation));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) simResult.get("body");
        assertEquals("ABC|ok", body.get("hasil"));
        assertEquals("ABC", body.get("id"));
    }
    @Test
    @DisplayName("simulasi cabang false mengembalikan 400")
    void simulateRejectedBranch() throws Exception {
        Map<String, Object> api = createTestApi("sim-bad-" + System.nanoTime());
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/simulate")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "versionId", versionId,
                                "method", "POST",
                                "body", Map.of("amount", 0)))))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> simulation = readMap(result);
        assertEquals(400, simulation.get("statusCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) simulation.get("body");
        assertEquals("4007302", body.get("responseCode"));
    }

    // ------------------------------------------------ test suite & publish

    @Test
    @DisplayName("test suite dijalankan dan hasilnya tercatat")
    void runTestSuite() throws Exception {
        Map<String, Object> api = createTestApi("suite-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("versionId", versionId);
        testCase.put("name", "Transfer sukses");
        testCase.put("method", "POST");
        testCase.put("path", api.get("path"));
        testCase.put("body", Map.of("amount", 15000));
        testCase.put("assertions", List.of(
                Map.of("type", "response.status", "expected", 200, "operator", "equals"),
                Map.of("type", "response.jsonpath", "target", "responseCode",
                        "expected", "2007300", "operator", "equals")));

        mvc.perform(post("/api/apis/" + apiId + "/tests")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(testCase)))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/apis/" + apiId + "/tests/run")
                        .header("Authorization", "Bearer " + qaToken)
                        .param("versionId", versionId))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> summary = readMap(result);
        assertEquals(1, summary.get("total"));
        assertEquals(1, summary.get("passed"));
        assertEquals(0, summary.get("failed"));
        assertEquals(true, summary.get("success"));
    }

    @Test
    @DisplayName("alur lengkap: preflight, publish, deploy, gateway, rollback")
    void fullPublishDeployAndRollback() throws Exception {
        Map<String, Object> api = createTestApi("e2e-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String apiPath = String.valueOf(api.get("path"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        // 1. simpan flow
        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        // 2. preflight harus siap (tanpa approval di dev)
        MvcResult preflight = mvc.perform(get("/api/versions/" + versionId + "/preflight")
                        .param("environment", "dev")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(true, readMap(preflight).get("ready"),
                () -> "preflight harus siap: " + safeBody(preflight));

        // 3. publish
        MvcResult published = mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> release = readMap(published);
        assertNotNull(release.get("artifactHash"));
        String releaseId = String.valueOf(release.get("id"));

        // 4. deploy ke dev
        MvcResult deployed = mvc.perform(post("/api/releases/" + releaseId + "/deploy")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "environment", "dev", "strategy", "DIRECT"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> deployment = readMap(deployed);
        assertEquals("ACTIVE", deployment.get("status"));
        assertEquals(100, deployment.get("canaryPercent"));

        // 5. panggil lewat gateway (policy auth=none agar tanpa API key)
        MvcResult gateway = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 15000))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> gatewayBody = readMap(gateway);
        assertEquals("2007300", gatewayBody.get("responseCode"));

        // 6. versi published tidak dapat diubah (immutable)
        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isConflict());

        // 7. publish ulang versi yang sama ditolak
        mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isConflict());

        // 8. rollback tanpa versi sebelumnya ditolak
        mvc.perform(post("/api/apis/" + apiId + "/rollback")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isConflict());

        // 9. observability mencatat request gateway
        MvcResult metrics = mvc.perform(get("/api/observability/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> series = readMap(metrics);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) series.get("summary");
        assertTrue(((Number) summary.get("totalRequests")).longValue() >= 1);
    }

    @Test
    @DisplayName("rollback mengaktifkan kembali versi sebelumnya")
    void rollbackRestoresPreviousVersion() throws Exception {
        Map<String, Object> api = createTestApi("rb-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String apiPath = String.valueOf(api.get("path"));
        String version1 = String.valueOf(api.get("draftVersionId"));

        // versi 1
        mvc.perform(put("/api/versions/" + version1 + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());
        MvcResult published1 = mvc.perform(post("/api/versions/" + version1 + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk())
                .andReturn();
        String release1 = String.valueOf(readMap(published1).get("id"));
        mvc.perform(post("/api/releases/" + release1 + "/deploy")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());

        // versi 2 dengan response berbeda
        MvcResult created = mvc.perform(post("/api/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("changeNote", "v2"))))
                .andExpect(status().isOk())
                .andReturn();
        String version2 = String.valueOf(readMap(created).get("id"));

        Map<String, Object> flowV2 = simpleFlow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = new java.util.ArrayList<>(
                (List<Map<String, Object>>) flowV2.get("nodes"));
        nodes.replaceAll(node -> "resp_ok".equals(node.get("id"))
                ? Map.of("id", "resp_ok", "type", "RESPONSE", "label", "Response Sukses",
                        "config", Map.of("status", 200, "body", Map.of(
                                "responseCode", "2007400", "versi", "v2")),
                        "position", Map.of("x", 560, "y", 140))
                : node);
        Map<String, Object> updatedFlow = new LinkedHashMap<>(flowV2);
        updatedFlow.put("nodes", nodes);

        mvc.perform(put("/api/versions/" + version2 + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", updatedFlow))))
                .andExpect(status().isOk());
        MvcResult published2 = mvc.perform(post("/api/versions/" + version2 + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk())
                .andReturn();
        String release2 = String.valueOf(readMap(published2).get("id"));
        mvc.perform(post("/api/releases/" + release2 + "/deploy")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());

        // v2 aktif
        MvcResult gatewayV2 = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 100))))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("2007400", readMap(gatewayV2).get("responseCode"));

        // rollback
        mvc.perform(post("/api/apis/" + apiId + "/rollback")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());

        // versi 1 kembali aktif
        MvcResult gatewayV1 = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 100))))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("2007300", readMap(gatewayV1).get("responseCode"));
    }

    // ------------------------------------------------- approval production

    @Test
    @DisplayName("publish production wajib approval")
    void productionRequiresApproval() throws Exception {
        Map<String, Object> api = createTestApi("prod-" + System.nanoTime());
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        // preflight production tidak siap karena belum ada approval
        MvcResult preflight = mvc.perform(get("/api/versions/" + versionId + "/preflight")
                        .param("environment", "production")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(false, readMap(preflight).get("ready"));

        // publish langsung ditolak
        mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "production"))))
                .andExpect(status().isConflict());

        // ajukan approval
        MvcResult requested = mvc.perform(post("/api/versions/" + versionId + "/request-publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "environment", "production", "reason", "rilis perdana"))))
                .andExpect(status().isOk())
                .andReturn();
        String approvalId = String.valueOf(readMap(requested).get("id"));

        // reviewer menyetujui
        mvc.perform(post("/api/approvals/" + approvalId + "/decide")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("approve", true, "note", "disetujui"))))
                .andExpect(status().isOk());

        // publish sekarang berhasil
        mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "production"))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------- secret

    @Test
    @DisplayName("secret tidak pernah dikembalikan dalam bentuk asli")
    void secretValueIsMasked() throws Exception {
        String name = "uji.secret." + System.nanoTime();
        mvc.perform(post("/api/secrets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", name,
                                "environment", "dev",
                                "value", "nilai-sangat-rahasia"))))
                .andExpect(status().isOk());

        MvcResult list = mvc.perform(get("/api/secrets")
                        .param("environment", "dev")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = safeBody(list);
        assertFalse(body.contains("nilai-sangat-rahasia"), "nilai secret tidak boleh muncul di respons");
        assertTrue(body.contains("****"), "hint masked harus ditampilkan");
    }

    @Test
    @DisplayName("QA tidak boleh menulis secret (403)")
    void qaCannotWriteSecret() throws Exception {
        mvc.perform(post("/api/secrets")
                        .header("Authorization", "Bearer " + qaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "x", "environment", "dev", "value", "y"))))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------- gateway

    @Test
    @DisplayName("gateway menolak API key tidak dikenal (401)")
    void gatewayRejectsUnknownApiKey() throws Exception {
        Map<String, Object> api = createTestApi("auth-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String apiPath = String.valueOf(api.get("path"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        // API dengan auth apiKey
        mvc.perform(put("/api/apis/" + apiId)
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "policyJson", Map.of("auth", "apiKey")))))
                .andExpect(status().isOk());

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());
        MvcResult releases = mvc.perform(get("/api/apis/" + apiId + "/releases")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        String releaseId = String.valueOf(readList(releases).get(0).get("id"));
        mvc.perform(post("/api/releases/" + releaseId + "/deploy")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());

        // tanpa key -> 401
        MvcResult withoutKey = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 1))))
                .andReturn();
        assertEquals(401, withoutKey.getResponse().getStatus());

        // key palsu -> 401
        MvcResult badKey = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .header("X-API-Key", "key_palsu_tidak_terdaftar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 1))))
                .andReturn();
        assertEquals(401, badKey.getResponse().getStatus());

        // key sah -> 200
        MvcResult consumer = mvc.perform(post("/api/consumers")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Consumer Uji", "allowedApiIds", apiId))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> consumerView = readMap(consumer);
        String apiKey = String.valueOf(consumerView.get("apiKey"));
        assertTrue(apiKey.startsWith("key_"));

        MvcResult withKey = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 500))))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("2007300", readMap(withKey).get("responseCode"));
    }

    @Test
    @DisplayName("gateway mengembalikan 404 untuk path tidak terdaftar")
    void gatewayUnknownPath() throws Exception {
        MvcResult result = mvc.perform(post("/gw/tidak/terdaftar")
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
    }

    @Test
    @DisplayName("gateway menolak consumer di luar allowedApiIds (403)")
    void gatewayRejectsConsumerOutsideAllowList() throws Exception {
        Map<String, Object> api = createTestApi("allow-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String apiPath = String.valueOf(api.get("path"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/apis/" + apiId)
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("policyJson", Map.of("auth", "apiKey")))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());
        MvcResult releases = mvc.perform(get("/api/apis/" + apiId + "/releases")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        String releaseId = String.valueOf(readList(releases).get(0).get("id"));
        mvc.perform(post("/api/releases/" + releaseId + "/deploy")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("environment", "dev"))))
                .andExpect(status().isOk());

        // consumer hanya boleh API lain
        MvcResult consumer = mvc.perform(post("/api/consumers")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "name", "Consumer Terbatas", "allowedApiIds", "api-lain"))))
                .andExpect(status().isOk())
                .andReturn();
        String apiKey = String.valueOf(readMap(consumer).get("apiKey"));

        MvcResult result = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("amount", 1))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
    }

    @Test
    @DisplayName("API dinonaktifkan tidak menerima traffic (503)")
    void disabledApiReturns503() throws Exception {
        Map<String, Object> api = createTestApi("off-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String apiPath = String.valueOf(api.get("path"));

        mvc.perform(put("/api/apis/" + apiId)
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("disabled", true))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/gw" + apiPath)
                        .param("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(503, result.getResponse().getStatus());
    }

    // ---------------------------------------------------- template & audit

    @Test
    @DisplayName("katalog template bawaan tersedia dan berkelompok")
    void builtinTemplatesAvailable() throws Exception {
        MvcResult result = mvc.perform(get("/api/templates")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> templates = readList(result);
        assertFalse(templates.isEmpty());
        assertTrue(templates.stream().anyMatch(t -> "SNAP BI".equals(t.get("category"))));

        MvcResult categories = mvc.perform(get("/api/templates/categories")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(readList(categories).isEmpty());
    }

    @Test
    @DisplayName("audit log mencatat aksi perubahan")
    void auditLogRecordsActions() throws Exception {
        Map<String, Object> api = createTestApi("audit-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));

        MvcResult result = mvc.perform(get("/api/audit")
                        .param("entityType", "Api")
                        .param("entityId", apiId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> logs = readList(result);
        assertTrue(logs.stream().anyMatch(log -> "API_CREATED".equals(log.get("action"))));
    }

    @Test
    @DisplayName("OpenAPI dihasilkan dari definisi flow")
    void openApiGenerated() throws Exception {
        Map<String, Object> api = createTestApi("oa-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));
        String versionId = String.valueOf(api.get("draftVersionId"));

        mvc.perform(put("/api/versions/" + versionId + "/flow")
                        .header("Authorization", "Bearer " + designerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("flow", simpleFlow()))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/apis/" + apiId + "/openapi")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> doc = readMap(result);
        assertEquals("3.0.3", doc.get("openapi"));
        assertTrue(((Map<?, ?>) doc.get("paths")).containsKey(api.get("path")));
        assertNotNull(doc.get("x-flow"));
    }

    @Test
    @DisplayName("hapus API membersihkan versinya")
    void deleteApiRemovesVersions() throws Exception {
        Map<String, Object> api = createTestApi("del-" + System.nanoTime());
        String apiId = String.valueOf(api.get("id"));

        mvc.perform(delete("/api/apis/" + apiId)
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/apis/" + apiId)
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("environment dev tidak mewajibkan approval, production mewajibkan")
    void environmentFlags() throws Exception {
        MvcResult result = mvc.perform(get("/api/environments")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> environments = readList(result);
        Map<String, Object> dev = environments.stream()
                .filter(e -> "dev".equals(e.get("code"))).findFirst().orElseThrow();
        Map<String, Object> production = environments.stream()
                .filter(e -> "production".equals(e.get("code"))).findFirst().orElseThrow();

        assertEquals(false, dev.get("requiresApproval"));
        assertEquals(true, production.get("requiresApproval"));
    }

    private static String safeBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            return "<body tidak terbaca>";
        }
    }
}
