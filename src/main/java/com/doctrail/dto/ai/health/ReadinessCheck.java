package com.doctrail.dto.ai.health;

public record ReadinessCheck(
        String name,
        Status status,
        String detail
) {
    public enum Status {
        UP,
        DOWN
    }
}
