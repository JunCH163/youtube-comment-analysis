package com.example.youtube_comment_analysis.ai;

import java.util.List;

import com.example.youtube_comment_analysis.video.CommentDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSentimentResponse(
		List<CommentDto> comments, 
        Integer detectedBotCount, 
        List<KeywordCount> topKeyword) {

}
