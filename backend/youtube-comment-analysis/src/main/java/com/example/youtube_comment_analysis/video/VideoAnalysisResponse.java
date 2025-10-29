package com.example.youtube_comment_analysis.video;

import java.util.List;

import com.example.youtube_comment_analysis.ai.KeywordCount;
//프론트 반환 json
public record VideoAnalysisResponse(
		VideoMeta video, 								
	    List<CommentDto> comments,			
	    List<KeywordCount> topKeywordGlobal,
        StatsDto stats,
        int commentCountBeforeBot,             
        int commentCountAfterBot ) {

}
