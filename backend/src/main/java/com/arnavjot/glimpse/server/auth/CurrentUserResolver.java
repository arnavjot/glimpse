package com.arnavjot.glimpse.server.auth;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Fills {@code @CurrentUser AuthUser} parameters from "Authorization: Bearer &lt;token&gt;".
 * No token, a bad signature or an expired token: 401, before the endpoint runs. The
 * app answers an expired-token 401 by refreshing and retrying once.
 */
@Configuration
public class CurrentUserResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final TokenService tokens;

    public CurrentUserResolver(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                    NativeWebRequest request, WebDataBinderFactory binders) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first");
        }
        return tokens.verify(header.substring(7).trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token expired or invalid"));
    }
}
