package dev.overlook.web.dto;

/** Corpo de erro da API local: {@code {"error": {...}}}. */
public record ApiError(Body error) {

    public record Body(String code, String message, String action, Integer retryAfterSeconds) {
    }

    public static ApiError of(String code, String message, String action, Integer retryAfterSeconds) {
        return new ApiError(new Body(code, message, action, retryAfterSeconds));
    }
}
