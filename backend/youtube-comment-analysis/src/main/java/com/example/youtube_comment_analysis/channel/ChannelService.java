package com.example.youtube_comment_analysis.channel;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.youtube_comment_analysis.video.VideoService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ChannelService {

	private final VideoService videoService;
	private final WebClient yt;
	
	public ChannelService(@Qualifier("youtubeWebClient") WebClient yt, VideoService videoService) {
        this.videoService = videoService;
		this.yt = yt;
    }
	
	@Value("${youtube.api.key}")
    private String apikey;
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	public ChannelAnalysisResponse getChannelData(String channelId) {
		try {
			String channelJson=yt.get()
					.uri(b->b.path("/search")
							.queryParam("part", "snippet")
							.queryParam("maxResults", "5")
							.queryParam("channelId", channelId)
							.queryParam("type", "video")
							.queryParam("key", apikey)
							.build())
					.retrieve()
					.bodyToMono(String.class)
					.block();
			
			ChannelMeta meta;
		}
		catch(Exception e) {
			
		}
		return null;
	}
}
