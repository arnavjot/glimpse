package com.arnavjot.glimpse.server.common;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

/**
 * Errors as {"detail": "..."}. An invalid body is 422 (the request was understood
 * but its content is wrong), which the app treats as "don't retry this batch".
 * An invalid query or path parameter (limit=0, a malformed id) is 400.
 */
@RestControllerAdvice
public class ApiErrors {

    /** 429 with Retry-After (seconds), so clients know when to try again. */
    @ExceptionHandler(RateLimiter.LimitedException.class)
    ResponseEntity<Map<String, String>> limited(RateLimiter.LimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds))
                .body(Map.of("detail", e.getReason()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("detail", e.getReason() == null ? "error" : e.getReason()));
    }

    /** A path or query value of the wrong type, e.g. /v1/uploads/not-a-uuid: 400, not 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> wrongType(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", e.getName() + ": invalid value"));
    }

    /** @Validated controllers report bad @RequestParam / @PathVariable values this way. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, String>> badParameter(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> lastNode(v.getPropertyPath().toString()) + ": " + v.getMessage())
                .orElse("invalid parameter");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", detail));
    }

    /** "feed.first" -> "first": the parameter name, without the Java method. */
    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        String detail = e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null
                ? m.getBindingResult().getFieldError().getField() + ": "
                        + m.getBindingResult().getFieldError().getDefaultMessage()
                : "invalid request";
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", detail));
    }
}
