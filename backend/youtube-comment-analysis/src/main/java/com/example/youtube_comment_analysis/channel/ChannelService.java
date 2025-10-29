package com.example.youtube_comment_analysis.channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.youtube_comment_analysis.ai.AiSender;
import com.example.youtube_comment_analysis.ai.KeywordCount;
import com.example.youtube_comment_analysis.error.ChannelAnalysisException;
import com.example.youtube_comment_analysis.error.ChannelNotFoundException;
import com.example.youtube_comment_analysis.error.ExternalServiceException;
import com.example.youtube_comment_analysis.error.PlaylistEmptyException;
import com.example.youtube_comment_analysis.video.VideoAnalysisResponse;
import com.example.youtube_comment_analysis.video.VideoMeta;
import com.example.youtube_comment_analysis.video.VideoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ChannelService {

	private final VideoService videoService;
	private final AiSender aiSender;
	private final WebClient yt;
	
	public ChannelService(@Qualifier("youtubeWebClient") WebClient yt, AiSender aiSender, VideoService videoService) {
        this.yt = yt;
        this.aiSender=aiSender;
        this.videoService = videoService;
    }
	
	@Value("${youtube.api.key}")
    private String apikey;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public ChannelAnalysisResponse getChannelData(String channelId) {
		String handle=channelId.startsWith("@") ? channelId : "@" + channelId;
		
		try {
			//채널의 메타 데이터(id, 이름, 설명, 개설일, 썸네일) 받기 
			String channelJson=yt.get()
					.uri(b->b.path("/channels")
							.queryParam("part", "snippet,contentDetails,statistics")
							.queryParam("forHandle", handle)
							.queryParam("key", apikey)
							.build())
					.retrieve()
					 .onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
				                .map(body -> new ExternalServiceException("YouTube 4xx: " + body, null)))
				            .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
				                .map(body -> new ExternalServiceException("YouTube 5xx: " + body, null)))
		            .bodyToMono(String.class)
		            .block();
			
			JsonNode root = mapper.readTree(channelJson);
			var itemsNode = root.path("items");
			if (!itemsNode.isArray() || itemsNode.size() == 0) {
			    throw new IllegalStateException("채널을 찾지 못했음: " + handle);
			}
			JsonNode ch = itemsNode.get(0);
			
			ChannelMeta meta=parseChannelMeta(channelJson);
			
			String PlaylistId=ch.path("contentDetails")
					.path("relatedPlaylists")
					.path("uploads")
					.asText();
			
			if (PlaylistId == null || PlaylistId.isBlank()) {
	            throw new PlaylistEmptyException("업로드 플레이리스트를 찾지 못함: channelId=" + meta.id());
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
					.onStatus(HttpStatusCode::is4xxClientError, res -> res.bodyToMono(String.class)
							.map(body -> new ExternalServiceException("YouTube 4xx: " + body, null)))
			        .onStatus(HttpStatusCode::is5xxServerError, res -> res.bodyToMono(String.class)
			                .map(body -> new ExternalServiceException("YouTube 5xx: " + body, null)))
		            .bodyToMono(String.class)
		            .block();
			var pRoot = mapper.readTree(playlistJson);
	        var pItems = pRoot.path("items");
	        if (!pItems.isArray() || pItems.size() == 0) {
	            throw new PlaylistEmptyException("업로드 영상이 비어있음: channelId=" + meta.id());
	        }
			
			
			List<VideoAnalysisResponse> videos=new ArrayList<>();
			List<VideoMeta> vMeta=new ArrayList<>();
			ArrayNode items=(ArrayNode)mapper.readTree(playlistJson).path("items");
			int pos=0, neu=0, neg=0;
			
			//videoservice의 영상 분석 함수 재활용
			for(JsonNode it : items) {
				String videoId=it.path("contentDetails").path("videoId").asText();
				if(videoId==null || videoId.isBlank())
					continue;
				try {
					var vr=this.videoService.getVideoData(videoId, 200);
					videos.add(vr);
					vMeta.add(vr.video());
					pos+=vr.POSITIVE();
					neu+=vr.NEUTRAL();
					neg+=vr.NEGATIVE();
				}
				catch(Exception e) {
					log.warn("video analysis failed: videoId={}", videoId, e);
				}
			}
			
			if(videos.isEmpty()) {
				//채널만 있고 영상이 없음
				return new ChannelAnalysisResponse(meta, List.of(), List.of(), 0, 0, 0, 0, 0);
			}
			
			
			//키워드 추출
			Map<String, Integer> channelglobalKeyword=new HashMap<>(256);
		    for(var vr:videos) {
		    	var keyword=vr.topKeywordGlobal();
		    	if(keyword==null)
		    		continue;
		    	for(var kc:keyword) {
		    		if(kc==null || kc.keyword()==null)
		    			continue;
		    		String key=kc.keyword().trim();
		    		int add=Math.max(0, kc.count());
		    		channelglobalKeyword.merge(key, add, Integer::sum);
		    	}
		    }
		    
		    List<KeywordCount> topKeywordGlobal=aiSender.getGlobalKeyword(channelglobalKeyword, 5); 
		
			//봇 개수
			int beforeSum = videos.stream().mapToInt(VideoAnalysisResponse::commentCountBeforeBot).sum();
			int afterSum  = videos.stream().mapToInt(VideoAnalysisResponse::commentCountAfterBot).sum();
			

	        return new ChannelAnalysisResponse(
	        		meta,
	        		vMeta,
	        		topKeywordGlobal,
	        		beforeSum,
	        		afterSum,
	        		pos,
	        		neu,
	        		neg);
		}
		catch(WebClientResponseException | WebClientRequestException e) {
			throw new ExternalServiceException("YouTube API 호출 실패: " + e.getMessage(), e);
		}
		catch (ChannelNotFoundException | PlaylistEmptyException e) {
	        throw e;
		}
		catch(Exception e) {
			throw new ChannelAnalysisException("채널 분석 중 내부 오류", e);
		}
	}
	
	private ChannelMeta parseChannelMeta(String channelJson) {
        try {
            JsonNode root = mapper.readTree(channelJson);
            JsonNode item = (root.path("items").isArray() && root.path("items").size() > 0)
                    ? root.path("items").get(0) : mapper.createObjectNode();

            JsonNode snippet = item.path("snippet");
            JsonNode statistics=item.path("statistics");
            
            String id=item.path("id").asText();
            String title = snippet.path("title").asText(null);
            String description = snippet.path("description").asText(null);
            String publishedAt = snippet.path("publishedAt").asText(null);
            String thumbnails = snippet.path("thumbnails").path("high").path("url").asText();
            
            Long viewCount=statistics.path("viewCount").asLong();
            Long subscriberCount=statistics.path("subscriberCount").asLong();
            Long videoCount=statistics.path("videoCount").asLong();
            

            return new ChannelMeta(id, title, description, publishedAt, thumbnails, viewCount, subscriberCount, videoCount);
        } catch (Exception e) {
            log.error("channel meta parse error", e);
            return new ChannelMeta(null, null, null, null, null,null,null,null);
        }
    }
}
