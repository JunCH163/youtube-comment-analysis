package com.example.youtube_comment_analysis.error;

public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException(String msg) { super(msg); }
}
