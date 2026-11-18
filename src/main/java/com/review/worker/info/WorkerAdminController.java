package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.WorkerConfigResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

/**
 * REQ-7: admin-only worker configuration and model-list endpoints, gated by
 * {@code WORKER_ADMIN_TOKEN} (Bearer token in Authorization header).
 * Accessible only on the info port (8082); blocked on the actuator port (8081) by
 * {@link PortIsolationInterceptor}.
 *
 * <p>Token comparison uses SHA-256 hashes of both sides to produce constant-length byte arrays before
 * {@link MessageDigest#isEqual} — this eliminates the length timing side-channel that a direct
 * {@code equals()} on variable-length UTF-8 strings would expose.
 */
@RestController
public class WorkerAdminController {

    private static final Logger log = LoggerFactory.getLogger(WorkerAdminController.class);

    private final WorkerInfoService infoService;
    private final WorkerConfigService configService;
    private final WorkerProperties props;

    public WorkerAdminController(WorkerInfoService infoService,
                                  WorkerConfigService configService,
                                  WorkerProperties props) {
        this.infoService = infoService;
        this.configService = configService;
        this.props = props;
    }

    @GetMapping("/worker/config")
    public ResponseEntity<?> getConfig(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return checkToken(auth).orElseGet(() ->
                ResponseEntity.ok(infoService.assembleConfigResponse()));
    }

    @GetMapping("/worker/models")
    public ResponseEntity<?> getModels(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<ResponseEntity<?>> check = checkToken(auth);
        if (check.isPresent()) {
            return check.get();
        }
        try {
            return ResponseEntity.ok(configService.listModels());
        } catch (IOException e) {
            log.warn("models scan failed", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "IO_ERROR", "message", "models directory unreadable"));
        }
    }

    /**
     * Returns an empty Optional when the token is valid; a present Optional with an error response
     * when it is missing, wrong, or when admin endpoints are disabled (no token configured).
     */
    private Optional<ResponseEntity<?>> checkToken(String auth) {
        String configured = props.getInfo().getAdminToken();
        if (configured == null || configured.isBlank()) {
            return Optional.of(ResponseEntity.status(503)
                    .body(Map.of("error", "ADMIN_DISABLED", "message", "Admin endpoints disabled")));
        }
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Optional.of(ResponseEntity.status(403).<Void>build());
        }
        byte[] provided = auth.substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h1 = md.digest(provided);
            md.reset();
            byte[] h2 = md.digest(expected);
            if (!MessageDigest.isEqual(h1, h2)) {
                return Optional.of(ResponseEntity.status(403).<Void>build());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return Optional.empty();
    }
}
