package com.arnavjot.glimpse.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Access tokens are short-lived signed JWTs (HS256): any API server can check one
 * without a database round trip. Refresh tokens are long random strings; the server
 * keeps only their SHA-256, see AuthService.
 */
@Component
public class TokenService {

    static final String ISSUER = "glimpse";
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final Duration accessTtl;
    private final Clock clock;

    @Autowired
    public TokenService(AuthProperties props) {
        this(props, Clock.systemUTC());
    }

    TokenService(AuthProperties props, Clock clock) {
        String secret = props.jwtSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("JWT_SECRET is not set: using a random key; sign-ins end when the server restarts");
            key = new byte[32];
            RANDOM.nextBytes(key);
        } else {
            key = secret.getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        this.accessTtl = Duration.ofMinutes(props.accessTokenMinutes());
        this.clock = clock;
    }

    public long accessTokenSeconds() {
        return accessTtl.toSeconds();
    }

    public String issueAccessToken(AuthUser user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .claim("handle", user.handle())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("can't sign a token", e);
        }
    }

    /** The user of a valid, unexpired access token from this server; empty otherwise. */
    public Optional<AuthUser> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return Optional.empty();
            if (!jwt.verify(new MACVerifier(key))) return Optional.empty();
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Date exp = c.getExpirationTime();
            if (!ISSUER.equals(c.getIssuer()) || exp == null || !exp.toInstant().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(UUID.fromString(c.getSubject()), c.getStringClaim("handle")));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 256 random bits, URL-safe: the refresh token the app keeps. */
    public static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What the server stores for a refresh token: its SHA-256 (hex). */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
