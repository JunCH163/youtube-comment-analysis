package com.example.youtube_comment_analysis.channel;

import java.util.List;

import com.example.youtube_comment_analysis.ai.KeywordCount;
import com.example.youtube_comment_analysis.video.CommentDto;
import com.example.youtube_comment_analysis.video.StatsDto;
import com.example.youtube_comment_analysis.video.VideoMeta;

public record ChannelAnalysisResponse(
		ChannelMeta channel,
		List<VideoMeta> videoMetas,
		List<KeywordCount> topKeywordGlobal,
		int commentCountBeforeBot,               
	    int commentCountAfterBot,
	    int POSITIVE,
        int NEUTRAL,
        int NEGATIVE) {
}
