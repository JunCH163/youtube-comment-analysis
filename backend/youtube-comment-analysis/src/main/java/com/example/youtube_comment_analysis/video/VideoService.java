package com.example.youtube_comment_analysis.video;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.youtube_comment_analysis.ai.AiSender;
import com.example.youtube_comment_analysis.ai.SendResult;
import com.example.youtube_comment_analysis.error.ExternalServiceException;
import com.example.youtube_comment_analysis.error.VideoAnalysisException;
import com.example.youtube_comment_analysis.error.VideoNotFoundException;
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

    //영상 개별 데이터 조회 함수
    public VideoAnalysisResponse getVideoData(String videoId, int limit) {
        try {
            //영상 메타데이터 조회
            String videoJson = yt.get()
                    .uri(b -> b.path("/videos")
                            .queryParam("part", "snippet,statistics")
                            .queryParam("id", videoId)
                            .queryParam("key", apikey)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                    	res.bodyToMono(String.class).map(body ->
                    		new ExternalServiceException("YouTube 4xx on /videos: " + body, null)))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                    	res.bodyToMono(String.class).map(body ->
                    		new ExternalServiceException("YouTube 5xx on /videos: " + body, null)))
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode vroot = mapper.readTree(videoJson);
            JsonNode vitems = vroot.path("items");
            if (!vitems.isArray() || vitems.size() == 0) {
                throw new VideoNotFoundException("비디오를 찾지 못함: videoId=" + videoId);
            }
            
            VideoMeta meta = parseVideoMeta(videoJson);

            //댓글 데이터 조회
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
                        	res.bodyToMono(String.class).map(body ->
                        		new ExternalServiceException("YouTube 4xx on /commentThreads: " + body, null)))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                        	res.bodyToMono(String.class).map(body ->
                            	new ExternalServiceException("YouTube 5xx on /commentThreads: " + body, null)))
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

                        if (commentId != null) {
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
                }

                pageToken = croot.path("nextPageToken").isMissingNode() ? null : croot.path("nextPageToken").asText(null);
                remain -= pageSize;

                if (pageToken == null) 
                	break;
            }

            int beforeBot = comments.size();
            
            

            //AI Sender 호출 (FastAPI와 연동)
            SendResult sendResult;
            try {
            	sendResult = aiSender.send(comments);
            }
            catch(WebClientResponseException | WebClientRequestException | TimeoutException e) {
            	throw new ExternalServiceException("AI 서버 호출 실패: " + e.getMessage(), e);
            }
            catch (Exception e) {
                throw new VideoAnalysisException("AI 분류 처리 중 오류", e);
            }

            //감정 통계 계산
            ZoneId zone = ZoneId.of("Asia/Seoul");
            StatsDto stats = buildStats(sendResult.comments(), zone);

            int afterBot = sendResult.comments().size();
          
            return new VideoAnalysisResponse(
                    meta,
                    sendResult.comments(),
                    sendResult.topKeywordGlobal(),
                    stats,
                    beforeBot,           
                    afterBot,
                    sendResult.POSITIVE(),
                    sendResult.NEUTRAL(),
                    sendResult.NEGATIVE()
            );
            
        }
        catch (WebClientResponseException e) { // HTTP status 있는 오류
            throw new ExternalServiceException("YouTube 응답 오류: " + e.getRawStatusCode() + " " + e.getStatusText(), e);
        }
        catch (WebClientRequestException e) {  // DNS/연결 등 I/O 오류
            throw new ExternalServiceException("YouTube 네트워크 오류: " + e.getMessage(), e);
        }
        catch (TimeoutException e) {
            throw new ExternalServiceException("YouTube 응답 지연(Timeout)", e);
        }
        catch (VideoNotFoundException e) {
            throw e; // 그대로 404로 올림
        }
        catch (ExternalServiceException e) {
            throw e; // 그대로 502/504로 올림
        }
        catch (Exception e) {
            // 파싱/로직 등 나머지 내부 오류
            throw new VideoAnalysisException("영상 분석 중 내부 오류", e);
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
            String thumbnails = snippet.path("thumbnails").path("high").path("url").asText(null);

            Long viewCount    = stats.path("viewCount").isMissingNode() ? null : stats.path("viewCount").asLong();
            Long likeCount    = stats.path("likeCount").isMissingNode() ? null : stats.path("likeCount").asLong();
            Long commentCount = stats.path("commentCount").isMissingNode() ? null : stats.path("commentCount").asLong();

            return new VideoMeta(title, channelId, channelTitle, publishedAt, viewCount, likeCount, commentCount,thumbnails);
        } catch (Exception e) {
            log.error("video meta parse error", e);
            return new VideoMeta(null, null, null, null, null, null, null,null);
        }
    }

    //댓글 활동 분석
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

    public static StatsDto buildStats(List<CommentDto> comments, ZoneId zone) {
        StatsDto stats = new StatsDto();

        // 감정별 TOP 좋아요 추적용
        Map<Sentiment, Integer> topLikes = new EnumMap<>(Sentiment.class);
        for (Sentiment s : Sentiment.values()) topLikes.put(s, Integer.MIN_VALUE);

        for (CommentDto c : comments) {
            // 1) 감정 매핑 (AI 기준: 0=부정, 1=중립, 2=긍정)
            Sentiment s = Sentiment.fromPrediction(c.getPrediction());
            stats.incTotal(s);

            // 2) 시간대 버킷 (2시간 단위) — “02시 라벨은 00:00~01:59”
            // publishedAt: UTC ISO-8601 가정
            if (c.getPublishedAt() != null) {
                LocalDateTime local = OffsetDateTime.parse(c.getPublishedAt())
                        .atZoneSameInstant(zone)
                        .toLocalDateTime();

                int hour = local.getHour();               // 0~23
                int label = ((hour / 2) + 1) * 2;         // 2,4,...,24
                if (label == 24) label = 0;               // 24 → 0

                // 0→idx0, 2→idx1, ..., 22→idx11
                stats.getHourly().get(label / 2).inc(s);
            }

            // 3) 감정별 좋아요 최댓값 댓글
            int likes = (c.getLikeCount() == null) ? 0 : c.getLikeCount().intValue();
            if (likes > topLikes.get(s)) {
                topLikes.put(s, likes);
                stats.getTopLikedBySentiment().put(s, c);
            }
        }
        return stats;
    }
}
