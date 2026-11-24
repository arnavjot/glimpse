package com.arnavjot.glimpse.server.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On an {@link AuthUser} controller parameter: the endpoint needs a signed-in user. The
 * request must carry "Authorization: Bearer &lt;access token&gt;", or it gets a 401.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
