package com.example.youtube_comment_analysis.ai;

import java.util.List;

public record AiSentimentRequest(List<Comment>comments, Trace trace) {
	public record Comment(String id, String author,String text, Long likeCount, String publishedAt,Integer prediction) {}
	public record Trace(String requestId, String analysisETag) {}
}
