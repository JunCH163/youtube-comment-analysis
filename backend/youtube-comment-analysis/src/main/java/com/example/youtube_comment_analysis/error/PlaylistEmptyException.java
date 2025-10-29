package com.example.youtube_comment_analysis.error;

public class PlaylistEmptyException extends RuntimeException {
    public PlaylistEmptyException(String msg) { super(msg); }
}
