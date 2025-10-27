package com.example.youtube_comment_analysis.channel;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/channel")
public class ChannelController {
	
	private final ChannelService channelService;

	@GetMapping("/{channelId}")
	public ResponseEntity<?> getChannelData(@PathVariable("channelId")String channelId){
		return ResponseEntity.ok(channelService.getChannelData(channelId));
	}
}
