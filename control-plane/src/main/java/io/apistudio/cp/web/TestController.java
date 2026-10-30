package io.apistudio.cp.web;

import io.apistudio.cp.domain.TestCase;
import io.apistudio.cp.domain.TestRun;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.service.AssertionEngine;
import io.apistudio.cp.service.TestRunnerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Simulasi dan test suite (BRD FR-SIM-01..09).
 *
 * <p>Endpoint test suite juga menjadi pemicu untuk CI/CD: pipeline cukup
 * memanggil {@code POST /api/apis/{id}/tests/run} dengan token yang memiliki
 * izin TEST_RUN (FR-SIM-09).
 */
@RestController
@RequestMapping("/api")
public class TestController {

    private final TestRunnerService testRunner;
    private final AuthService auth;

    public TestController(TestRunnerService testRunner, AuthService auth) {
        this.testRunner = testRunner;
        this.auth = auth;
    }

    /** Simulasi ad-hoc dari Studio (FR-SIM-01). */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(HttpServletRequest request,
                                        @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.TEST_RUN);
        return testRunner.runAdHoc(body, actor);
    }

    /** Daftar test case satu API. */
    @GetMapping("/apis/{apiId}/tests")
    public List<Map<String, Object>> list(HttpServletRequest request, @PathVariable String apiId) {
        auth.require(request, Permission.TEST_READ);
        return testRunner.testCasesOf(apiId).stream().map(testRunner::testCaseView).toList();
    }

    @GetMapping("/tests/{testId}")
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String testId) {
        auth.require(request, Permission.TEST_READ);
        return testRunner.testCaseView(testRunner.requireTestCase(testId));
    }

    @PostMapping("/apis/{apiId}/tests")
    public Map<String, Object> create(HttpServletRequest request,
                                      @PathVariable String apiId,
                                      @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.TEST_WRITE);
        TestCase saved = testRunner.saveTestCase(apiId, null, body, actor);
        return testRunner.testCaseView(saved);
    }

    @PutMapping("/tests/{testId}")
    public Map<String, Object> update(HttpServletRequest request,
                                      @PathVariable String testId,
                                      @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.TEST_WRITE);
        TestCase existing = testRunner.requireTestCase(testId);
        TestCase saved = testRunner.saveTestCase(existing.apiId, testId, body, actor);
        return testRunner.testCaseView(saved);
    }

    @DeleteMapping("/tests/{testId}")
    public Map<String, Object> delete(HttpServletRequest request, @PathVariable String testId) {
        CurrentUser actor = auth.require(request, Permission.TEST_WRITE);
        testRunner.deleteTestCase(testId, actor);
        return Map.of("ok", true);
    }

    /** Jalankan satu test case. */
    @PostMapping("/tests/{testId}/run")
    public Map<String, Object> run(HttpServletRequest request,
                                   @PathVariable String testId,
                                   @RequestParam(required = false) String environment) {
        CurrentUser actor = auth.require(request, Permission.TEST_RUN);
        return testRunner.runTestCase(testId, environment, actor);
    }

    /** Jalankan seluruh test suite satu API (gate publish + CI/CD). */
    @PostMapping("/apis/{apiId}/tests/run")
    public Map<String, Object> runSuite(HttpServletRequest request,
                                        @PathVariable String apiId,
                                        @RequestParam(required = false) String versionId,
                                        @RequestParam(required = false) String environment) {
        CurrentUser actor = auth.require(request, Permission.TEST_RUN);
        return testRunner.runSuite(apiId, versionId, environment, actor);
    }

    /** Riwayat run satu API. */
    @GetMapping("/apis/{apiId}/tests/history")
    public List<Map<String, Object>> history(HttpServletRequest request, @PathVariable String apiId) {
        auth.require(request, Permission.TEST_READ);
        return testRunner.history(apiId).stream().map(testRunner::runView).toList();
    }

    /** Jenis assertion yang didukung (untuk UI). */
    @GetMapping("/tests/assertion-types")
    public List<Map<String, Object>> assertionTypes(HttpServletRequest request) {
        auth.require(request, Permission.TEST_READ);
        return AssertionEngine.supportedTypes();
    }
}
