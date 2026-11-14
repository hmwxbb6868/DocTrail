package com.doctrail.security;

public record AuthenticatedIdentity(Long userId, String username, String role) {
}
