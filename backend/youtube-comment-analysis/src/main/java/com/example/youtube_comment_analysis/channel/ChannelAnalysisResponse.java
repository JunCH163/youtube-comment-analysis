package com.example.youtube_comment_analysis.channel;

import java.util.List;

import com.example.youtube_comment_analysis.KeywordCount;
import com.example.youtube_comment_analysis.video.CommentDto;

public record ChannelAnalysisResponse(
		ChannelMeta channel,
		List<CommentDto> comments,
		List<KeywordCount> topKeywordGlobal,
		int commentCountBeforeBot,               
	    int commentCountAfterBot) {
}
