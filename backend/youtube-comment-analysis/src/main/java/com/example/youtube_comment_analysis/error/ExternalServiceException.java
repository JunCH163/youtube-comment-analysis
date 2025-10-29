package com.example.youtube_comment_analysis.error;

public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String msg, Throwable cause) { super(msg, cause); }
}
