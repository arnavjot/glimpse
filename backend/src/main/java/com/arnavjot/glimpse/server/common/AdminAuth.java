package com.arnavjot.glimpse.server.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.push.PushProperties;

/**
 * Guards the admin endpoints (/v1/admin/...) with the X-Admin-Token header.
 * No ADMIN_TOKEN configured: they don't exist (404). Wrong token: 401.
 */
@Component
public class AdminAuth {

    private final PushProperties props;

    public AdminAuth(PushProperties props) {
        this.props = props;
    }

    /** Constant-time comparison, so the token can't be guessed byte by byte from response times. */
    public void require(String token) {
        String expected = props.adminToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // admin endpoints are off
        }
        boolean ok = token != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad admin token");
    }
}
