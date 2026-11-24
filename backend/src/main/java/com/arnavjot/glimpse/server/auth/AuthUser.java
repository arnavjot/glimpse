package com.arnavjot.glimpse.server.auth;

import java.util.UUID;

/** The signed-in user a request acts as (from its access token). */
public record AuthUser(UUID id, String handle) {}
