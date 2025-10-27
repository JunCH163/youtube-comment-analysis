package com.example.youtube_comment_analysis.channel;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.youtube_comment_analysis.video.VideoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ChannelService {

	private final VideoService videoService;
	private final WebClient yt;
	
	public ChannelService(@Qualifier("youtubeWebClient") WebClient yt, VideoService videoService) {
        this.yt = yt;
        this.videoService = videoService;
    }
	
	@Value("${youtube.api.key}")
    private String apikey;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public Map<String, Object> getChannelData(String channelId) {
		String handle=channelId.startsWith("@") ? channelId : "@" + channelId;
		
		try {
			//채널의 메타 데이터(id, 이름, 설명, 개설일, 썸네일) 받기 
			String channelJson=yt.get()
					.uri(b->b.path("/channels")
							.queryParam("part", "snippet,contentDetails")
							.queryParam("forHandle", handle)
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
			
			JsonNode ch=mapper.readTree(channelJson).path("items").get(0);
			
			ChannelMeta meta=parseChannelMeta(channelJson);
			
			String PlaylistId=ch.path("contentDetails")
					.path("relatedPlaylists")
					.path("uploads")
					.asText();
			
			if (PlaylistId == null || PlaylistId.isBlank()) {
                throw new IllegalStateException("업로드 플레이리스트를 찾을 수 없음: " + meta.id());
            }
			
			//채널의 최신 영상 5개
			String playlistJson=yt.get()
					.uri(b->b.path("/playlistItems")
							.queryParam("part", "contentDetails")
							.queryParam("playlistId", PlaylistId)
							.queryParam("maxResults", 5)
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
			/*
			ArrayNode items=(ArrayNode)mapper.readTree(playlistJson).path("items");
			
			for(JsonNode it : items) {
				this.videoService.getVideoData(it.path("id").path("videoId").asText(), 200);
			}
			*/
			
			Map<String, Object> result = new HashMap<>();
	        result.put("channelJson", mapper.readTree(channelJson));
	        result.put("playlistJson", mapper.readTree(playlistJson));
	        result.put("parsedMeta", meta);
	        result.put("playlistId", PlaylistId);
	        
	        return result;
		}
		catch(Exception e) {
			return null;
		}
	}
	
	private ChannelMeta parseChannelMeta(String channelJson) {
        try {
            JsonNode root = mapper.readTree(channelJson);
            JsonNode item = (root.path("items").isArray() && root.path("items").size() > 0)
                    ? root.path("items").get(0) : mapper.createObjectNode();

            JsonNode snippet = item.path("snippet");
            
            String id=item.path("id").asText();
            String title = snippet.path("title").asText(null);
            String description = snippet.path("description").asText(null);
            String publishedAt = snippet.path("publishedAt").asText(null);
            String thumbnails = snippet.path("thumbnails").path("high").path("url").asText();

            return new ChannelMeta(id, title, description, publishedAt, thumbnails);
        } catch (Exception e) {
            log.error("channel meta parse error", e);
            return new ChannelMeta(null, null, null, null, null);
        }
    }
	
}
