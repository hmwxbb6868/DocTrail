package com.doctrail.controller.user;

import com.doctrail.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the historical stream path contract-visible while real SSE remains
 * permanently out of the current route.
 */
@RestController
@RequestMapping("/api/chat/stream")
public class StreamCompatibilityController {
    @PostMapping("/messages")
    public ResponseEntity<Result<Void>> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Result.failure(5010, "streaming is not implemented in the current route"));
    }
}
