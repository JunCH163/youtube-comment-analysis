package com.example.youtube_comment_analysis.error;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private String traceId() {
        return UUID.randomUUID().toString();
    }
	
	@ExceptionHandler(ChannelNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleChannelNotFound(ChannelNotFoundException ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.NOT_FOUND.value(),
            "Channel Not Found",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
	
	@ExceptionHandler(PlaylistEmptyException.class)
    public ResponseEntity<ApiErrorResponse> handlePlaylistEmpty(PlaylistEmptyException ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.NOT_FOUND.value(),
            "Playlist Empty",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
	
	@ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleWebClientResponse(WebClientResponseException ex, HttpServletRequest req) {
        var status = HttpStatus.valueOf(ex.getRawStatusCode());
        var body = ApiErrorResponse.of(
            status.value(),
            "Upstream HTTP " + status.value(),
            ex.getResponseBodyAsString(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(status).body(body);
    }
	
	@ExceptionHandler({ WebClientRequestException.class, TimeoutException.class })
    public ResponseEntity<ApiErrorResponse> handleUpstreamIO(Exception ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.GATEWAY_TIMEOUT.value(),
            "Upstream Timeout/IO",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }
	
	@ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleExternalService(ExternalServiceException ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.BAD_GATEWAY.value(),
            "External Service Error",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
	
	@ExceptionHandler(ChannelAnalysisException.class)
    public ResponseEntity<ApiErrorResponse> handleChannelAnalysis(ChannelAnalysisException ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Channel Analysis Failed",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
	
	@ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        var body = ApiErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            ex.getMessage(),
            req.getRequestURI(),
            traceId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
	
	@ExceptionHandler(VideoNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleVideoNotFound(VideoNotFoundException ex, HttpServletRequest req) {
	    var body = ApiErrorResponse.of(
	        HttpStatus.NOT_FOUND.value(),
	        "Video Not Found",
	        ex.getMessage(),
	        req.getRequestURI(),
	        traceId()
	    );
	    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
	}
	
	@ExceptionHandler(VideoAnalysisException.class)
	public ResponseEntity<ApiErrorResponse> handleVideoAnalysis(VideoAnalysisException ex, HttpServletRequest req) {
	    var body = ApiErrorResponse.of(
	        HttpStatus.INTERNAL_SERVER_ERROR.value(),
	        "Video Analysis Failed",
	        ex.getMessage(),
	        req.getRequestURI(),
	        traceId()
	    );
	    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
	}
	
	@ExceptionHandler(CommentsDisabledException.class)
	public ResponseEntity<ApiErrorResponse> handleCommentsDisabled(CommentsDisabledException ex, HttpServletRequest req) {
	    var body = ApiErrorResponse.of(
	        HttpStatus.UNPROCESSABLE_ENTITY.value(),  // 422
	        "Comments Disabled",
	        ex.getMessage(),
	        req.getRequestURI(),
	        traceId()
	    );
	    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
	}
}
