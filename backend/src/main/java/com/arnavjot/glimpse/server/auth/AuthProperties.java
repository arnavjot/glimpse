package com.arnavjot.glimpse.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtSecret          HMAC key for access tokens (32+ chars); empty: random per start
 * @param accessTokenMinutes lifetime of an access token
 * @param refreshTokenDays   lifetime of a refresh token (each use replaces it)
 */
@ConfigurationProperties("glimpse.auth")
public record AuthProperties(String jwtSecret, int accessTokenMinutes, int refreshTokenDays) {}
