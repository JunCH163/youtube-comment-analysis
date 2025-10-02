package com.example.youtube_comment_analysis.video;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder // 빌더 패턴을 사용하면 객체 생성이 편리해집니다.
public class AnalysisDto {

    // 0시부터 23시까지의 시간대별 댓글 수를 담을 리스트
    private final List<Integer> hourlyCommentCount;

    // 댓글이 가장 많았던 시간
    private final Integer peakHour;

    // 활동이 가장 많았던 상위 3개 시간대
    private final List<Integer> topActiveHours;

    // 첫 댓글부터 마지막 댓글까지의 총 기간
    private final String totalCommentPeriod;

    // 시간당 평균 댓글 수
    private final Double averageCommentsPerHour;
}
