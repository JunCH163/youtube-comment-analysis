package com.example.youtube_comment_analysis.error;

public class VideoAnalysisException extends RuntimeException {
    public VideoAnalysisException(String msg, Throwable cause) { super(msg, cause); }
    public VideoAnalysisException(String msg) {
    	super(msg);
    }
}