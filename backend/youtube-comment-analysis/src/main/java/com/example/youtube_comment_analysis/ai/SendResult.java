package com.example.youtube_comment_analysis.ai;

import java.util.List;

import com.example.youtube_comment_analysis.video.CommentDto;

public record SendResult(
		List<CommentDto> comments,           // 봇 제거 후 최종 댓글(원본 순서 유지)
	    List<KeywordCount> topKeywordGlobal,
	    int POSITIVE,                        
	    int NEUTRAL,
	    int NEGATIVE) {
	
}
