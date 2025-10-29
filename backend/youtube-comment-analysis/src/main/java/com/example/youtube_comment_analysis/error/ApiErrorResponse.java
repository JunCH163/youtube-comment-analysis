package com.example.youtube_comment_analysis.error;

import java.time.Instant;

public record ApiErrorResponse(
		Instant timestamp,
	    int status,
	    String error,
	    String message,
	    String path,
	    String traceId) {
	
	public static ApiErrorResponse of(int status, String error, String message, String path, String traceId) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, traceId);
    }
}
