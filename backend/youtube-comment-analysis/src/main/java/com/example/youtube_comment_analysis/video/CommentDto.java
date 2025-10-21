package com.example.youtube_comment_analysis.video;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentDto {
	@JsonAlias({"id"})
    private String commentId;
    private String author;
    private String text;
    private Long likeCount;
    private String publishedAt;
    private Integer prediction;
}
