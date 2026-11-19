package com.review.worker.info;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PortIsolationInterceptor} — no Spring context, plain unit.
 */
class PortIsolationInterceptorTest {

    private static final int ACTUATOR_PORT = 8081;
    private static final int INFO_PORT = 8082;

    private PortIsolationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PortIsolationInterceptor(ACTUATOR_PORT, INFO_PORT);
    }

    // ---- happy-path: correct port for each route -----------------------------------------------

    @Test
    void workerInfoAllowedOnInfoPort() throws Exception {
        assertThat(handle(INFO_PORT, "/worker/info")).isTrue();
    }

    @Test
    void workerConfigAllowedOnInfoPort() throws Exception {
        assertThat(handle(INFO_PORT, "/worker/config")).isTrue();
    }

    @Test
    void workerModelsAllowedOnInfoPort() throws Exception {
        assertThat(handle(INFO_PORT, "/worker/models")).isTrue();
    }

    @Test
    void actuatorHealthAllowedOnActuatorPort() throws Exception {
        assertThat(handle(ACTUATOR_PORT, "/actuator/health")).isTrue();
    }

    @Test
    void actuatorPrometheusAllowedOnActuatorPort() throws Exception {
        assertThat(handle(ACTUATOR_PORT, "/actuator/prometheus")).isTrue();
    }

    // ---- port-isolation blocks -----------------------------------------------------------------

    @Test
    void workerInfoBlockedOnActuatorPort() throws Exception {
        MockHttpServletResponse res = handleCapture(ACTUATOR_PORT, "/worker/info");
        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void workerConfigBlockedOnActuatorPort() throws Exception {
        MockHttpServletResponse res = handleCapture(ACTUATOR_PORT, "/worker/config");
        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void actuatorHealthBlockedOnInfoPort() throws Exception {
        MockHttpServletResponse res = handleCapture(INFO_PORT, "/actuator/health");
        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void actuatorPrometheusBlockedOnInfoPort() throws Exception {
        MockHttpServletResponse res = handleCapture(INFO_PORT, "/actuator/prometheus");
        assertThat(res.getStatus()).isEqualTo(404);
    }

    // ---- suspicious URI patterns rejected on any port ------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/worker/../etc/passwd",
            "/worker/%2e%2e/secret",
            "/worker/info%2fextra",
            "/worker//info",
            "/worker/info;jsessionid=1"
    })
    void suspiciousUriRejectedWith400OnInfoPort(String uri) throws Exception {
        MockHttpServletResponse res = handleCapture(INFO_PORT, uri);
        assertThat(res.getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/../etc/passwd",
            "/actuator/%2e%2e/secret",
            "/actuator//health"
    })
    void suspiciousUriRejectedWith400OnActuatorPort(String uri) throws Exception {
        MockHttpServletResponse res = handleCapture(ACTUATOR_PORT, uri);
        assertThat(res.getStatus()).isEqualTo(400);
    }

    // ---- /health on both ports -----------------------------------------------------------------

    @Test
    void healthAllowedOnActuatorPort() throws Exception {
        assertThat(handle(ACTUATOR_PORT, "/health")).isTrue();
    }

    @Test
    void healthAllowedOnInfoPort() throws Exception {
        assertThat(handle(INFO_PORT, "/health")).isTrue();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private boolean handle(int port, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setLocalPort(port);
        MockHttpServletResponse res = new MockHttpServletResponse();
        return interceptor.preHandle(req, res, new Object());
    }

    private MockHttpServletResponse handleCapture(int port, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setLocalPort(port);
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req, res, new Object());
        return res;
    }
}
