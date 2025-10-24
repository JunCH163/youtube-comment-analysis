package com.example.youtube_comment_analysis.video;

import java.util.List;

import com.example.youtube_comment_analysis.KeywordCount;
//프론트 반환 json
public record VideoAnalysisResponse(
		VideoMeta video,                    //메타 데이터
	    List<CommentDto> comments,          //봇 제거 후 최종 댓글
	    int totalDetectedBotCount,
	    List<KeywordCount> topKeywordGlobal,
        StatsDto stats,
        int commentCountBeforeBot,                // ✅ 추가: 봇 필터 전 수집된 댓글 수
        int commentCountAfterBot ) {

}
