package com.doctrail.controller.operations;

import com.doctrail.dto.operations.JavaLivenessResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/health")
public class JavaLivenessController {
    private static final String STATUS_UP = "UP";
    private static final String SERVICE_NAME = "doctrail-java";

    @GetMapping("/live")
    public JavaLivenessResponse live() {
        return new JavaLivenessResponse(STATUS_UP, SERVICE_NAME, Instant.now().toString());
    }
}
