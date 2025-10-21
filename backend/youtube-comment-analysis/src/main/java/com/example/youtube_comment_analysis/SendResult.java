package com.example.youtube_comment_analysis;

import java.util.List;

import com.example.youtube_comment_analysis.video.CommentDto;

public record SendResult(
		List<CommentDto> comments,           // 봇 제거 후 최종 댓글(원본 순서 유지)
	    int totalDetectedBotCount,           // 모든 배치의 봇 합산
	    List<KeywordCount> topKeywordGlobal) {
	
}
