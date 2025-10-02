package com.example.youtube_comment_analysis.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import io.netty.handler.timeout.TimeoutException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class VideoService {

    private final WebClient webClient;
    private static final String YT_BASE = "https://www.googleapis.com/youtube/v3";

    @Value("${youtube.api.key}")
    private String apikey;

    private final ObjectMapper mapper = new ObjectMapper();

    public VideoResponse getVideoData(String videoId, int limit) {
        try {
            //영상 데이터
            String videoUrl = YT_BASE + "/videos"
                    + "?part=snippet,statistics"
                    + "&id=" + videoId
                    + "&key=" + apikey;

            String videoJson = webClient.get()
                    .uri(videoUrl)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new RuntimeException("클라이언트 오류 (API Key, 권한 등): " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            res.bodyToMono(String.class)
                                    .map(body -> new RuntimeException("유튜브 서버 오류: " + body)))
                    .bodyToMono(String.class)
                    .block();

            JsonNode vroot = mapper.readTree(videoJson);
            JsonNode items = vroot.path("items");

            if (!items.isArray() || items.size() == 0) {
                throw new IllegalArgumentException("영상 없음 : " + videoId);
            }

            JsonNode v0 = items.get(0);
            JsonNode snippet = v0.path("snippet");
            JsonNode stats = v0.path("statistics");

            VideoResponse resp = new VideoResponse();

            resp.setVideoId(videoId);
            resp.setTitle(snippet.path("title").asText(null));
            resp.setChannelId(snippet.path("channelId").asText(null));
            resp.setChannelTitle(snippet.path("channelTitle").asText(null));
            resp.setPublishedAt(snippet.path("publishedAt").asText(null));
            resp.setViewCount(stats.path("viewCount").isMissingNode() ? null : stats.path("viewCount").asLong());
            resp.setLikeCount(stats.path("likeCount").isMissingNode() ? null : stats.path("likeCount").asLong());
            resp.setCommentCount(stats.path("commentCount").isMissingNode() ? null : stats.path("commentCount").asLong());

            //댓글 데이터
            List<CommentDto> comments = new ArrayList<>();
            String pageToken = null;
            int remain = Math.max(0, limit);

            while (remain > 0) {
                int pageSize = Math.min(100, remain);  // 100건 씩 돌면서 갖고옴
                String ctUrl = YT_BASE + "/commentThreads"
                        + "?part=snippet,replies"
                        + "&textFormat=plainText"
                        + "&order=time"
                        + "&maxResults=" + pageSize
                        + "&videoId=" + videoId
                        + (pageToken != null ? "&pageToken=" + pageToken : "")
                        + "&key=" + apikey;

                String ctJson = webClient.get()
                        .uri(ctUrl)
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

                        comments.add(new CommentDto(
                                commentId,
                                author,
                                text,
                                likeCount,
                                publishedAt
                        ));
                    }
                }

                pageToken = croot.path("nextPageToken").isMissingNode() ? null : croot.path("nextPageToken").asText(null);
                remain -= pageSize;

                if (pageToken == null)
                    break;
            }

            resp.setComments(comments);
            return resp;
        }
        catch (WebClientRequestException e) {
            throw new RuntimeException("네트워크 오류: " + e.getMessage(), e);
        }
        catch (TimeoutException e) {
            throw new RuntimeException("YouTube API 응답 지연", e);
        }
        catch(Exception e){
            throw new RuntimeException("Failed to fetch YouTube data: " + e.getMessage(), e);
        }
    }

    // --- 분석 메소드 추가 ---
    public AnalysisDto analyzeCommentsActivity(String videoId) {
        // 1. 분석을 위해 충분한 댓글 데이터 가져오기 (최대 2000개)
        List<CommentDto> comments = getVideoData(videoId, 2000).getComments();

        if (comments == null || comments.isEmpty()) {
            // 댓글이 없는 경우 기본값으로 DTO를 빌드하여 반환
            return AnalysisDto.builder()
                    .hourlyCommentCount(List.of(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0))
                    .peakHour(0)
                    .topActiveHours(new ArrayList<>())
                    .totalCommentPeriod("데이터 없음")
                    .averageCommentsPerHour(0.0)
                    .build();
        }

        // 2. 분석을 위한 변수 초기화
        int[] hourlyCounts = new int[24];
        LocalDateTime firstCommentTime = null;
        LocalDateTime lastCommentTime = null;

        // 3. 댓글 목록 순회 및 분석
        for (CommentDto comment : comments) {
            // Z로 끝나는 ISO 8601 형식의 문자열을 LocalDateTime으로 파싱
            LocalDateTime publishedAt = LocalDateTime.parse(comment.getPublishedAt(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            int hour = publishedAt.getHour();
            hourlyCounts[hour]++;

            // 첫 댓글과 마지막 댓글 시간 업데이트
            if (firstCommentTime == null || publishedAt.isBefore(firstCommentTime)) {
                firstCommentTime = publishedAt;
            }
            if (lastCommentTime == null || publishedAt.isAfter(lastCommentTime)) {
                lastCommentTime = publishedAt;
            }
        }

        // 4. 통계 계산
        // - 최다 댓글 시간
        int peakHour = 0;
        int maxCount = 0;
        for (int i = 0; i < hourlyCounts.length; i++) {
            if (hourlyCounts[i] > maxCount) {
                maxCount = hourlyCounts[i];
                peakHour = i;
            }
        }

        // - 활동이 많은 상위 3개 시간대 (댓글 수가 많은 순)
        List<Integer> topActiveHours = IntStream.range(0, 24)
                .boxed()
                .sorted(Comparator.comparingInt(h -> hourlyCounts[((Integer) h).intValue()]).reversed())
                .limit(3)
                .collect(Collectors.toList());

        // - 댓글 작성 기간
        Duration duration = Duration.between(firstCommentTime, lastCommentTime);
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        String totalCommentPeriod = String.format("%d일 %d시간", days, hours);

        // - 시간당 평균 댓글 수
        double totalHours = duration.toSeconds() / 3600.0;
        double averageCommentsPerHour = (totalHours > 0) ? (double) comments.size() / totalHours : 0;


        // 5. AnalysisDto 객체 생성 및 반환
        return AnalysisDto.builder()
                .hourlyCommentCount(IntStream.of(hourlyCounts).boxed().collect(Collectors.toList()))
                .peakHour(peakHour)
                .topActiveHours(topActiveHours)
                .totalCommentPeriod(totalCommentPeriod)
                .averageCommentsPerHour(averageCommentsPerHour)
                .build();
    }
}