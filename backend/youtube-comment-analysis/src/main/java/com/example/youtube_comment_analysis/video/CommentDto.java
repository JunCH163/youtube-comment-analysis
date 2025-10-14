package com.example.youtube_comment_analysis.video;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentDto {
	
    private String commentId;
    private String author;
    private String text;
    private Long likeCount;
    private String publishedAt;
    private Integer prediction;
}
