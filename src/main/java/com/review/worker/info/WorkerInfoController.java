package com.review.worker.info;

import com.review.worker.info.dto.WorkerInfoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REQ-6: unauthenticated worker status endpoint.
 * Accessible only on the info port (8082); blocked on the actuator port (8081) by
 * {@link PortIsolationInterceptor}.
 */
@RestController
public class WorkerInfoController {

    private final WorkerInfoService infoService;

    public WorkerInfoController(WorkerInfoService infoService) {
        this.infoService = infoService;
    }

    @GetMapping("/worker/info")
    public ResponseEntity<WorkerInfoResponse> getInfo() {
        return ResponseEntity.ok(infoService.assembleInfoResponse());
    }
}
