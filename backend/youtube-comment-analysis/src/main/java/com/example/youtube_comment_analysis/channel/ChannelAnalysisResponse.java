package com.example.youtube_comment_analysis.channel;

import java.util.List;

import com.example.youtube_comment_analysis.ai.KeywordCount;
import com.example.youtube_comment_analysis.video.CommentDto;
import com.example.youtube_comment_analysis.video.StatsDto;

public record ChannelAnalysisResponse(
		ChannelMeta channel,						
		List<CommentDto> comments,
		List<KeywordCount> topKeywordGlobal,
		 StatsDto stats,
		int commentCountBeforeBot,               
	    int commentCountAfterBot) {
}
