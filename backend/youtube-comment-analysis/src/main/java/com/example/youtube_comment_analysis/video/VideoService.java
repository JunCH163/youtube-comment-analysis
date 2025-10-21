package com.example.youtube_comment_analysis.video;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.example.youtube_comment_analysis.AiSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.handler.timeout.TimeoutException;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VideoService {

    private final WebClient yt;
    private final AiSender aiSender;

    public VideoService(@Qualifier("youtubeWebClient") WebClient yt, AiSender aiSender) {
        this.yt = yt;
        this.aiSender = aiSender;
    }

    @Value("${youtube.api.key}")
    private String apikey;

    private final ObjectMapper mapper = new ObjectMapper();

    public VideoAnalysisResponse getVideoData(String videoId, int limit) {
        try {
            // --- 영상 메타데이터 조회 ---
            String videoJson = yt.get()
                    .uri(b -> b.path("/videos")
                            .queryParam("part", "snippet,statistics")
                            .queryParam("id", videoId)
                            .queryParam("key", apikey)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new RuntimeException("클라이언트 오류 (API Key, 권한 등): " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new RuntimeException("유튜브 서버 오류: " + body)))
                    .bodyToMono(String.class)
                    .block();
            
            VideoMeta meta = parseVideoMeta(videoJson);

            // --- 댓글 데이터 조회 ---
            List<CommentDto> comments = new ArrayList<>();
            String pageToken = null;
            int remain = Math.max(0, limit);

            while (remain > 0) {
                int pageSize = Math.min(100, remain); // 100개 단위로 요청
                final String token = pageToken;

                String ctJson = yt.get()
                        .uri(b -> b.path("/commentThreads")
                                .queryParam("part", "snippet,replies")
                                .queryParam("textFormat", "plainText")
                                .queryParam("order", "time")
                                .queryParam("maxResults", pageSize)
                                .queryParam("videoId", videoId)
                                .queryParam("key", apikey)
                                .queryParamIfPresent("pageToken", Optional.ofNullable(token))
                                .build())
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, res ->
                                res.bodyToMono(String.class)
                                        .map(body -> new RuntimeException("댓글 조회 오류: " + body)))
                        .onStatus(HttpStatusCode::is5xxServerError, res ->
                                res.bodyToMono(String.class)
                                        .map(body -> new RuntimeException("댓글 서버 오류: " + body)))
                        .bodyToMono(String.class)
                        .block();

                JsonNode croot = mapper.readTree(ctJson);
                JsonNode citems = croot.path("items");

                if (citems.isArray()) {
                    for (JsonNode it : citems) {
                        JsonNode top = it.path("snippet").path("topLevelComment");
                        String commentId = top.path("id").asText();
                        JsonNode cs = top.path("snippet");
                        String author = cs.path("authorDisplayName").asText(null);
                        String text = cs.path("textDisplay").asText(null);
                        long likeCount = cs.path("likeCount").asLong(0);
                        String publishedAt = cs.path("publishedAt").asText(null);
                        Integer prediction=0;

                        comments.add(new CommentDto(
                                commentId,
                                author,
                                text,
                                likeCount,
                                publishedAt,
                                prediction
                        ));
                    }
                }

                pageToken = croot.path("nextPageToken").isMissingNode() ? null : croot.path("nextPageToken").asText(null);
                remain -= pageSize;

                if (pageToken == null) 
                	break;
            }

            // --- AI Sender 호출 (FastAPI와 연동) ---
            var sendResult = aiSender.send(comments);
            
            
            //테스트 코드
            List<Integer> list=analyzeCommentsActivity(comments).getTopActiveHours();
            
            for(int l:list) {
            	System.out.println(l);
            }
            //테스트 코드
          
            return new VideoAnalysisResponse(
                    meta,
                    sendResult.comments(),
                    sendResult.totalDetectedBotCount(),
                    sendResult.topKeywordGlobal()
            );
            
        } catch (WebClientRequestException e) {
            throw new RuntimeException("네트워크 오류: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            throw new RuntimeException("YouTube API 응답 지연", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch YouTube data: " + e.getMessage(), e);
        }
    }
    
    private VideoMeta parseVideoMeta(String videoJson) {
        try {
            JsonNode root = mapper.readTree(videoJson);
            JsonNode item = (root.path("items").isArray() && root.path("items").size() > 0)
                    ? root.path("items").get(0) : mapper.createObjectNode();

            JsonNode snippet = item.path("snippet");
            JsonNode stats   = item.path("statistics");

            String title        = snippet.path("title").asText(null);
            String channelId    = snippet.path("channelId").asText(null);
            String channelTitle = snippet.path("channelTitle").asText(null);
            String publishedAt  = snippet.path("publishedAt").asText(null);

            Long viewCount    = stats.path("viewCount").isMissingNode() ? null : stats.path("viewCount").asLong();
            Long likeCount    = stats.path("likeCount").isMissingNode() ? null : stats.path("likeCount").asLong();
            Long commentCount = stats.path("commentCount").isMissingNode() ? null : stats.path("commentCount").asLong();

            return new VideoMeta(title, channelId, channelTitle, publishedAt, viewCount, likeCount, commentCount);
        } catch (Exception e) {
            log.error("video meta parse error", e);
            return new VideoMeta(null, null, null, null, null, null, null);
        }
    }

    // --- 추가: 댓글 활동 분석 ---
    public AnalysisDto analyzeCommentsActivity(List<CommentDto> comments) {
        
        if (comments == null || comments.isEmpty()) {
            return AnalysisDto.builder()
                    .hourlyCommentCount(List.of(new Integer[24])) // 24시간 0으로 초기화
                    .peakHour(0)
                    .topActiveHours(new ArrayList<>())
                    .totalCommentPeriod("데이터 없음")
                    .averageCommentsPerHour(0.0)
                    .build();
        }

        int[] hourlyCounts = new int[24];
        LocalDateTime firstCommentTime = null;
        LocalDateTime lastCommentTime = null;

        for (CommentDto comment : comments) {
            LocalDateTime publishedAt = LocalDateTime.parse(comment.getPublishedAt(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            int hour = publishedAt.getHour();
            hourlyCounts[hour]++;

            if (firstCommentTime == null || publishedAt.isBefore(firstCommentTime)) {
                firstCommentTime = publishedAt;
            }
            if (lastCommentTime == null || publishedAt.isAfter(lastCommentTime)) {
                lastCommentTime = publishedAt;
            }
        }

        // 최다 댓글 시간
        int peakHour = IntStream.range(0, 24)
                .boxed()
                .max(Comparator.comparingInt(h -> hourlyCounts[h]))
                .orElse(0);

        // 활동 많은 상위 3개 시간대
        List<Integer> topActiveHours = IntStream.range(0, 24)
                .boxed()
                .sorted((h1, h2) -> Integer.compare(hourlyCounts[h2], hourlyCounts[h1]))
                .limit(3)
                .collect(Collectors.toList());

        // 댓글 작성 기간
        Duration duration = Duration.between(firstCommentTime, lastCommentTime);
        String totalCommentPeriod = String.format("%d일 %d시간", duration.toDays(), duration.toHours() % 24);

        // 시간당 평균 댓글 수
        double totalHours = duration.toSeconds() / 3600.0;
        double averageCommentsPerHour = (totalHours > 0) ? (double) comments.size() / totalHours : 0;

        return AnalysisDto.builder()
                .hourlyCommentCount(IntStream.of(hourlyCounts).boxed().collect(Collectors.toList()))
                .peakHour(peakHour)
                .topActiveHours(topActiveHours)
                .totalCommentPeriod(totalCommentPeriod)
                .averageCommentsPerHour(averageCommentsPerHour)
                .build();
    }
}
