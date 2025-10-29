package com.example.youtube_comment_analysis.video;

public record VideoMeta(
		String id,
		String title,
	    String channelId,
	    String channelTitle,
	    String publishedAt,
	    Long viewCount,
	    Long likeCount,
	    Long commentCount,
	    String thumbnails) {

}
