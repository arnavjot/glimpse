package com.arnavjot.glimpse.server.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.arnavjot.glimpse.server.common.RateLimitProperties;
import com.arnavjot.glimpse.server.common.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creator accounts: register, sign in, refresh a session, sign out, and who am I.
 * Every successful sign-in returns a short-lived access token (send it as
 * "Authorization: Bearer ...") and a refresh token (exchange it for new tokens).
 */
@RestController
public class AuthController {

    public record Register(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.]{3,30}") String handle) {}

    public record Login(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, Account user) {
        static TokenResponse of(AuthService.Tokens t) {
            return new TokenResponse(t.accessToken(), t.refreshToken(), t.expiresIn(),
                    new Account(t.user().id().toString(), t.user().handle()));
        }
    }

    public record Account(String id, String handle) {}

    private final AuthService auth;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;

    public AuthController(AuthService auth, RateLimiter limiter, RateLimitProperties limits) {
        this.auth = auth;
        this.limiter = limiter;
        this.limits = limits;
    }

    // Per-IP limits, checked before any BCrypt work: the per-email limit stops guessing one
    // account's password, these stop one machine trying many accounts or tying up the CPU.
    // getRemoteAddr() is the real client: behind nginx, Tomcat takes it from X-Forwarded-For,
    // but only when the request comes from the trusted proxy (server.tomcat.remoteip).


    @PostMapping("/v1/auth/register")
    public ResponseEntity<TokenResponse> register(@RequestBody @Valid Register r, HttpServletRequest request) {
        limiter.take(limits.register(), request.getRemoteAddr(), "sign-ups from your network");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TokenResponse.of(auth.register(r.email(), r.password(), r.handle())));
    }

    @PostMapping("/v1/auth/login")
    public TokenResponse login(@RequestBody @Valid Login l, HttpServletRequest request) {
        limiter.take(limits.login(), request.getRemoteAddr(), "sign-in attempts from your network");
        return TokenResponse.of(auth.login(l.email(), l.password()));
    }

    @PostMapping("/v1/auth/refresh")
    public TokenResponse refresh(@RequestBody @Valid RefreshRequest r, HttpServletRequest request) {
        limiter.take(limits.refresh(), request.getRemoteAddr(), "requests from your network");
        return TokenResponse.of(auth.refresh(r.refreshToken()));
    }

    @PostMapping("/v1/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody @Valid RefreshRequest r) {
        auth.logout(r.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/me")
    public Map<String, String> me(@CurrentUser AuthUser user) {
        return Map.of("id", user.id().toString(), "handle", user.handle(),
                "email", auth.emailOf(user.id()).orElse(""));
    }
}
