package com.arnavjot.glimpse.server.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.common.RedisBreaker;

/**
 * Accounts and sessions.
 *
 * <ul>
 *   <li>Passwords: BCrypt. A login for an unknown email still runs one BCrypt check, so
 *       response time doesn't reveal which emails have accounts.</li>
 *   <li>Brute force: at most {@link #MAX_ATTEMPTS} logins per email per
 *       {@link #ATTEMPT_WINDOW} (counted in Redis; if Redis is down, logins still work).</li>
 *   <li>Refresh tokens rotate on every use. Presenting one that was already used means
 *       it was copied: every session of that user is ended (except a quick retry after a
 *       lost response, see {@link #REUSE_GRACE}).</li>
 * </ul>
 */
@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken, long expiresIn, AuthUser user) {}

    static final int MAX_ATTEMPTS = 10;
    /**
     * A rotated refresh token presented again within this time, while its replacement is
     * still unused, is a client retrying after a lost response, not theft. A stolen token
     * used in that window still trips detection later: the real client's next refresh
     * uses a token that was retired here, which ends every session.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final StringRedisTemplate redis;
    private final RedisBreaker breaker;
    private final Duration refreshTtl;
    private final Clock clock;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    /** Hash of a random password, checked when the email is unknown (equal timing). */
    private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());

    @Autowired
    public AuthService(JdbcTemplate jdbc, TokenService tokens, StringRedisTemplate redis, AuthProperties props,
                       RedisBreaker breaker) {
        this(jdbc, tokens, redis, props, breaker, Clock.systemUTC());
    }

    AuthService(JdbcTemplate jdbc, TokenService tokens, StringRedisTemplate redis, AuthProperties props,
                RedisBreaker breaker, Clock clock) {
        this.jdbc = jdbc;
        this.breaker = breaker;
        this.tokens = tokens;
        this.redis = redis;
        this.refreshTtl = Duration.ofDays(props.refreshTokenDays());
        this.clock = clock;
    }

    @Transactional
    public Tokens register(String email, String password, String handle) {
        String normalized = normalize(email);
        String h = handle.trim().toLowerCase(Locale.ROOT);
        // Checked before the insert: after a failed INSERT, Postgres refuses every further
        // statement in the transaction, so the reason couldn't be looked up afterwards.
        if (exists("SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)", normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "that email already has an account");
        }
        if (exists("SELECT EXISTS (SELECT 1 FROM users WHERE handle = ?)", h)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "that handle is taken");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id, email, handle, password_hash) VALUES (?, ?, ?, ?)",
                    id, normalized, h, bcrypt.encode(password));
        } catch (DuplicateKeyException e) { // someone registered the same in between
            throw new ResponseStatusException(HttpStatus.CONFLICT, "that email or handle was just taken");
        }
        return issue(new AuthUser(id, h));
    }

    private boolean exists(String sql, String arg) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arg));
    }

    @Transactional
    public Tokens login(String email, String password) {
        String normalized = normalize(email);
        countAttempt(normalized);
        List<String[]> rows = jdbc.query("SELECT id, handle, password_hash FROM users WHERE email = ?",
                (rs, i) -> new String[] {rs.getString("id"), rs.getString("handle"), rs.getString("password_hash")},
                normalized);
        if (rows.isEmpty()) {
            bcrypt.matches(password, dummyHash); // same cost as a real check
            throw badLogin();
        }
        String[] row = rows.get(0);
        if (!bcrypt.matches(password, row[2])) throw badLogin();
        return issue(new AuthUser(UUID.fromString(row[0]), row[1]));
    }

    // noRollbackFor: on token reuse the "end every session" update must stick even
    // though the request then fails with 401.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Tokens refresh(String refreshToken) {
        String hash = TokenService.hash(refreshToken);
        // in_grace: revoked within the grace period, by a rotation whose new token has never
        // been used. (Times compared in the database, the clock that set revoked_at.)
        List<Object[]> rows = jdbc.query("""
                SELECT r.id, r.user_id, r.expires_at, r.revoked_at, u.handle, r.replaced_by,
                       (r.revoked_at > now() - make_interval(secs => ?)
                        AND EXISTS (SELECT 1 FROM refresh_tokens n
                                     WHERE n.id = r.replaced_by AND n.revoked_at IS NULL)) AS in_grace
                  FROM refresh_tokens r JOIN users u ON u.id = r.user_id
                 WHERE r.token_hash = ?""",
                (rs, i) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at"), rs.getString("handle"),
                        rs.getObject("replaced_by", UUID.class), rs.getBoolean("in_grace")},
                REUSE_GRACE.toSeconds(), hash);
        if (rows.isEmpty()) throw badRefresh();
        Object[] r = rows.get(0);
        UUID tokenId = (UUID) r[0];
        UUID userId = (UUID) r[1];
        if (r[3] != null) {
            if (Boolean.TRUE.equals(r[6])) {
                // A retry: the client sent this token moments ago but never got the answer
                // (connection dropped). Retire the unused replacement and issue a new pair,
                // instead of treating it as theft and signing the user out everywhere.
                int retired = jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
                        r[5]);
                if (retired == 1) {
                    Tokens next = issue(new AuthUser(userId, (String) r[4]));
                    jdbc.update("UPDATE refresh_tokens SET replaced_by = (SELECT id FROM refresh_tokens WHERE token_hash = ?) "
                            + "WHERE id = ?", TokenService.hash(next.refreshToken()), tokenId);
                    return next;
                }
            }
            revokeAll(userId);
            log.warn("Refresh token reuse for user {}: all sessions revoked", userId);
            throw badRefresh();
        }
        if (!((Instant) r[2]).isAfter(clock.instant())) throw badRefresh();
        // Claim the old token atomically: of two requests racing with the same token,
        // only one gets a new session.
        int claimed = jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL",
                tokenId);
        if (claimed == 0) throw badRefresh();
        Tokens next = issue(new AuthUser(userId, (String) r[4]));
        jdbc.update("UPDATE refresh_tokens SET replaced_by = (SELECT id FROM refresh_tokens WHERE token_hash = ?) "
                + "WHERE id = ?", TokenService.hash(next.refreshToken()), tokenId);
        return next;
    }

    /** Ends one session. Unknown or already-ended tokens are fine (idempotent). */
    public void logout(String refreshToken) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL",
                TokenService.hash(refreshToken));
    }

    public Optional<String> emailOf(UUID userId) {
        return jdbc.query("SELECT email FROM users WHERE id = ?", (rs, i) -> rs.getString("email"), userId)
                .stream().findFirst();
    }

    private Tokens issue(AuthUser user) {
        String refresh = TokenService.newRefreshToken();
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), user.id(), TokenService.hash(refresh),
                Timestamp.from(clock.instant().plus(refreshTtl)));
        return new Tokens(tokens.issueAccessToken(user), refresh, tokens.accessTokenSeconds(), user);
    }

    private void revokeAll(UUID userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    private void countAttempt(String email) {
        String key = "login-attempts:" + email;
        try {
            Long n = breaker.call(() -> {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1) redis.expire(key, ATTEMPT_WINDOW);
                return count;
            });
            if (n != null && n > MAX_ATTEMPTS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts, try again later");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RedisBreaker.OpenException e) {
            // Redis already known to be down: allow, without a log line per attempt.
        } catch (RuntimeException e) {
            log.warn("Login throttling unavailable (Redis): allowing the attempt", e);
        }
    }

    private static ResponseStatusException badLogin() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong email or password");
    }

    private static ResponseStatusException badRefresh() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session expired, sign in again");
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
