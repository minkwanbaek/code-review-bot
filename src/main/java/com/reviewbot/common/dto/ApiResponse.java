package com.reviewbot.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard JSON response envelope for Review Bot API endpoints.
 *
 * @param <T> payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    /**
     * Creates a successful response.
     *
     * @param data response payload
     * @param <T> payload type
     * @return successful response
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /**
     * Creates a failed response.
     *
     * @param message failure message
     * @param <T> payload type
     * @return failed response
     */
    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, null, message);
    }

    /**
     * Returns whether the request succeeded.
     *
     * @return success flag
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the standard payload.
     *
     * @return payload
     */
    public T getData() {
        return data;
    }

    /**
     * Returns an error or informational message.
     *
     * @return message
     */
    public String getMessage() {
        return message;
    }
}
