package io.apistudio.cp.bootstrap;

import io.apistudio.contracts.flow.ErrorPolicy;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.contracts.flow.Position;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiGroup;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.domain.AppUser;
import io.apistudio.cp.domain.Environment;
import io.apistudio.cp.domain.FunctionTemplate;
import io.apistudio.cp.domain.TemplateVersion;
import io.apistudio.cp.domain.TestCase;
import io.apistudio.cp.repo.ApiGroupRepository;
import io.apistudio.cp.repo.ApiRepository;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.repo.AppUserRepository;
import io.apistudio.cp.repo.EnvironmentRepository;
import io.apistudio.cp.repo.FunctionTemplateRepository;
import io.apistudio.cp.repo.TestCaseRepository;
import io.apistudio.cp.repo.TemplateVersionRepository;
import io.apistudio.cp.service.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap platform (BRD Bagian 10.4 langkah 6).
 *
 * <p>Menyiapkan:
 * <ul>
 *   <li>Administrator pertama dan akun demo untuk setiap peran.</li>
 *   <li>Environment dev/staging/production beserta instance Flow Engine.</li>
 *   <li>Katalog function template bawaan.</li>
 *   <li>Contoh API SNAP BI agar alur UC-01..UC-06 dapat langsung dicoba.</li>
 * </ul>
 *
 * <p>Operasi ini idempotent: bootstrap hanya berjalan bila data belum ada,
 * sehingga aman dijalankan berulang pada database persisten.
 */
@Component
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final AppUserRepository users;
    private final EnvironmentRepository environments;
    private final ApiGroupRepository groups;
    private final ApiRepository apis;
    private final ApiVersionRepository versions;
    private final TestCaseRepository testCases;
    private final FunctionTemplateRepository templates;
    private final TemplateVersionRepository templateVersions;
    private final JsonStore json;
    private final String adminUsername;
    private final String adminPassword;
    private final boolean seedDemo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PlatformBootstrap(AppUserRepository users,
                             EnvironmentRepository environments,
                             ApiGroupRepository groups,
                             ApiRepository apis,
                             ApiVersionRepository versions,
                             TestCaseRepository testCases,
                             FunctionTemplateRepository templates,
                             TemplateVersionRepository templateVersions,
                             JsonStore json,
                             @Value("${apistudio.bootstrap.admin-username:admin}") String adminUsername,
                             @Value("${apistudio.bootstrap.admin-password:admin123}") String adminPassword,
                             @Value("${apistudio.bootstrap.seed-demo:true}") boolean seedDemo) {
        this.users = users;
        this.environments = environments;
        this.groups = groups;
        this.apis = apis;
        this.versions = versions;
        this.testCases = testCases;
        this.templates = templates;
        this.templateVersions = templateVersions;
        this.json = json;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.seedDemo = seedDemo;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        seedEnvironments();
        seedTemplates();
        if (seedDemo) {
            seedDemoApi();
        }
    }

    // ------------------------------------------------------------- pengguna

    private void seedUsers() {
        if (!users.existsByUsername(adminUsername)) {
            AppUser admin = new AppUser(adminUsername, "Administrator", adminUsername + "@example.com", "ADMIN");
            admin.passwordHash = encoder.encode(adminPassword);
            admin.systemUser = true;
            users.save(admin);
            log.info("Administrator pertama dibuat: {}", adminUsername);
        }

        // Akun demo per peran agar RBAC dapat langsung dicoba.
        Map<String, String> demoAccounts = new LinkedHashMap<>();
        demoAccounts.put("designer", "DESIGNER");
        demoAccounts.put("reviewer", "REVIEWER");
        demoAccounts.put("qa", "QA");
        demoAccounts.put("operator", "OPERATOR");
        demoAccounts.put("auditor", "AUDITOR");

        demoAccounts.forEach((username, role) -> {
            if (!users.existsByUsername(username)) {
                AppUser user = new AppUser(username, capitalize(username), username + "@example.com", role);
                user.passwordHash = encoder.encode("password123");
                user.systemUser = true;
                users.save(user);
            }
        });
        log.info("Akun demo RBAC disiapkan: {} akun", demoAccounts.size());
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // ---------------------------------------------------------- environment

    private void seedEnvironments() {
        if (environments.count() > 0) {
            return;
        }
        Environment dev = new Environment("dev", "Development", false, true, true);
        dev.description = "Pengembangan dan eksperimen; mock dan sandbox DB diizinkan.";
        dev.instancesJson = json.write(List.of(
                Map.of("id", "engine-dev-1", "baseUrl", "http://localhost:8080", "zone", "local")));

        Environment staging = new Environment("staging", "Staging", true, true, true);
        staging.description = "Uji integrasi dan UAT; menyerupai produksi.";
        staging.instancesJson = json.write(List.of(
                Map.of("id", "engine-staging-1", "baseUrl", "http://localhost:8080", "zone", "local")));

        Environment production = new Environment("production", "Production", true, false, true);
        production.description = "Layanan nyata; approval wajib dan canary disarankan.";
        production.instancesJson = json.write(List.of(
                Map.of("id", "engine-prod-1", "baseUrl", "http://localhost:8080", "zone", "zone-a"),
                Map.of("id", "engine-prod-2", "baseUrl", "http://localhost:8080", "zone", "zone-b")));

        environments.saveAll(List.of(dev, staging, production));
        log.info("Environment dev, staging, production disiapkan");
    }

    // ------------------------------------------------------------ template

    private void seedTemplates() {
        int added = 0;
        for (BuiltinTemplateCatalog.Definition definition : BuiltinTemplateCatalog.definitions()) {
            if (templates.findByTemplateKey(definition.templateKey()).isPresent()) {
                continue;
            }
            FunctionTemplate template = new FunctionTemplate(
                    definition.templateKey(),
                    definition.name(),
                    definition.category(),
                    definition.description(),
                    true,
                    "system");
            FunctionTemplate saved = templates.save(template);
            TemplateVersion version = BuiltinTemplateCatalog.toVersion(definition, saved.id, "system");
            templateVersions.save(version);
            added++;
        }
        if (added > 0) {
            log.info("Katalog template bawaan: {} template ditambahkan", added);
        }
    }

    // ----------------------------------------------------------- API demo

    private void seedDemoApi() {
        if (apis.count() > 0) {
            return;
        }
        ApiGroup payments = new ApiGroup("Payments", "API pembayaran dan transfer", "system");
        payments.color = "emerald";
        ApiGroup savedGroup = groups.save(payments);

        ApiEntity api = new ApiEntity(savedGroup.id, "Transfer Antar Bank",
                "/v1.0/transfer-intrabank", "POST",
                "Contoh transfer intrabank dengan validasi header, pemrosesan Java, "
                        + "dan pengecekan rekening tujuan.",
                "payment,snapbi", "system");
        // Kebijakan default memakai API key agar jalur autentikasi dapat diuji.
        api.policyJson = json.write(Map.of(
                "auth", "apiKey",
                "rateLimitPerMinute", 120,
                "ipWhitelist", List.of()));
        ApiEntity savedApi = apis.save(api);

        ApiVersion draft = new ApiVersion(savedApi.id, 1, "system");
        draft.flowJson = json.write(demoFlow());
        draft.policyJson = savedApi.policyJson;
        draft.javaSourcesJson = json.write(Map.of("node_java", Map.of(
                "source", demoJavaSource(),
                "libraries", List.of())));
        draft.changeNote = "Versi awal contoh SNAP BI transfer";
        // Seed default agar node DATABASE_QUERY punya tabel saat dipanggil gateway.
        draft.seedsJson = json.write(List.of(Map.of(
                "table", "account",
                "rows", List.of(Map.of("account_no", "1234567890", "balance", "1000000")))));
        ApiVersion savedVersion = versions.save(draft);

        seedDemoTestCases(savedApi, savedVersion);
        log.info("Contoh API SNAP BI dibuat: {} {}", savedApi.method, savedApi.path);
    }

    private void seedDemoTestCases(ApiEntity api, ApiVersion version) {
        TestCase happyPath = new TestCase(api.id, version.id, "Transfer sukses", "system");
        happyPath.method = "POST";
        happyPath.path = api.path;
        happyPath.headersJson = json.write(Map.of(
                "X-TIMESTAMP", "2026-10-29T10:00:00+07:00",
                "X-SIGNATURE", "dummy",
                "X-PARTNER-ID", "PARTNER-001"));
        happyPath.bodyJson = json.write(Map.of(
                "partnerReferenceNo", "REF-0001",
                "amount", Map.of("value", "150000.00", "currency", "IDR"),
                "beneficiaryAccountName", "Siti",
                "beneficiaryAccountNo", "1234567890",
                "remark", "Transfer contoh"));
        happyPath.seedJson = json.write(List.of(Map.of(
                "table", "account",
                "rows", List.of(Map.of("account_no", "1234567890", "balance", "1000000")))));
        happyPath.assertionsJson = json.write(List.of(
                Map.of("type", "response.status", "expected", 200, "operator", "equals"),
                Map.of("type", "response.jsonpath", "target", "responseCode",
                        "expected", "2007300", "operator", "equals"),
                Map.of("type", "trace.nodeExecuted", "target", "node_validate"),
                Map.of("type", "db.rowcount", "sql", "select * from account",
                        "expected", 1, "operator", "equals")));
        testCases.save(happyPath);

        TestCase validationFailure = new TestCase(api.id, version.id,
                "Transfer ditolak nominal nol", "system");
        validationFailure.method = "POST";
        validationFailure.path = api.path;
        validationFailure.headersJson = happyPath.headersJson;
        validationFailure.bodyJson = json.write(Map.of(
                "partnerReferenceNo", "REF-0002",
                "amount", Map.of("value", "0", "currency", "IDR"),
                "beneficiaryAccountName", "Siti",
                "beneficiaryAccountNo", "1234567890"));
        validationFailure.seedJson = happyPath.seedJson;
        validationFailure.assertionsJson = json.write(List.of(
                Map.of("type", "response.status", "expected", 400, "operator", "equals"),
                Map.of("type", "response.jsonpath", "target", "responseCode",
                        "expected", "4007302", "operator", "equals"),
                Map.of("type", "trace.nodeSkipped", "target", "node_java")));
        testCases.save(validationFailure);
    }

    /**
     * Flow contoh: trigger -> validasi header -> kondisi nominal -> Java code ->
     * query database -> response.
     */
    private FlowDefinition demoFlow() {
        FlowNode trigger = node("trigger", NodeType.TRIGGER, "Request Masuk", 80, 240, Map.of());

        FlowNode validate = node("node_validate", NodeType.TEMPLATE_CALL, "Validasi Header SNAP BI",
                280, 240, Map.of(
                        "templateKey", "snapbi.timestamp",
                        "version", 1,
                        "args", Map.of()));

        FlowNode condition = node("node_condition", NodeType.CONDITION, "Nominal > 0?", 480, 240,
                Map.of("expression", "=number(request.body.amount.value) > 0"));

        FlowNode javaNode = node("node_java", NodeType.JAVA_CODE, "Susun Response", 700, 140,
                Map.of("artifactKey", "node_java", "source", demoJavaSource(), "libraries", List.of()));

        FlowNode dbQuery = node("node_db", NodeType.DATABASE_QUERY, "Cek Rekening Tujuan", 700, 360,
                Map.of("sql", "select * from account where account_no = ?",
                        "params", List.of("=request.body.beneficiaryAccountNo")));

        FlowNode responseOk = node("node_response", NodeType.RESPONSE, "Response Sukses", 940, 240,
                Map.of("status", 200,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of(
                                "responseCode", "2007300",
                                "responseMessage", "Successful",
                                "referenceNo", "=request.body.partnerReferenceNo",
                                "amount", Map.of(
                                        "value", "=request.body.amount.value",
                                        "currency", "=request.body.amount.currency"),
                                "beneficiaryAccountNo", "=request.body.beneficiaryAccountNo")));

        FlowNode responseBad = node("node_response_error", NodeType.RESPONSE,
                "Response Gagal Validasi", 940, 440, Map.of("status", 400,
                        "body", Map.of(
                                "responseCode", "4007302",
                                "responseMessage", "Invalid Amount")));

        List<FlowEdge> edges = List.of(
                new FlowEdge("e1", "trigger", "node_validate", FlowEdge.PORT_OUT, null),
                new FlowEdge("e2", "node_validate", "node_condition", FlowEdge.PORT_OUT, null),
                new FlowEdge("e3", "node_condition", "node_java", FlowEdge.PORT_TRUE, "ya"),
                new FlowEdge("e4", "node_condition", "node_response_error", FlowEdge.PORT_FALSE, "tidak"),
                new FlowEdge("e5", "node_java", "node_db", FlowEdge.PORT_OUT, null),
                new FlowEdge("e6", "node_db", "node_response", FlowEdge.PORT_OUT, null));

        return new FlowDefinition(null, "Transfer Antar Bank",
                List.of(trigger, validate, condition, javaNode, dbQuery, responseOk, responseBad),
                edges, FlowDefinition.SCHEMA_VERSION);
    }

    private FlowNode node(String id, NodeType type, String label, double x, double y,
                          Map<String, Object> config) {
        return new FlowNode(id, type, label, new LinkedHashMap<>(config), new Position(x, y),
                ErrorPolicy.defaults(), false);
    }

    /** Kode Java contoh yang memakai NodeContext (FR-JAVA-02/06). */
    private String demoJavaSource() {
        return """
                // Kode pengguna: isi method run(). Variabel flow tersedia lewat ctx.
                String refNo = ctx.input("partnerReferenceNo") == null
                        ? "REF-AUTO" : ctx.input("partnerReferenceNo").toString();
                ctx.set("referenceNo", refNo);

                // memanggil function template dari dalam kode (FR-JAVA-06)
                Object ts = ctx.callTemplate("snapbi.timestamp@1", java.util.Map.of());
                ctx.log("Timestamp SNAP BI: " + ts);

                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("referenceNo", ctx.get("referenceNo"));
                out.put("processedAt", ts);
                return out;
                """;
    }
}
