package com.example.youtube_comment_analysis.error;

public class CommentsDisabledException extends RuntimeException{
	public CommentsDisabledException(String msg) { 
        super(msg); 
    }
}
