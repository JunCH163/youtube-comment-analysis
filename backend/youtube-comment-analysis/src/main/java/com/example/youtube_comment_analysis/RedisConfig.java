package com.example.youtube_comment_analysis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.example.youtube_comment_analysis.video.VideoAnalysisResponse;

@Configuration
public class RedisConfig {
	
	//영상 redis L2캐쉬(영상 메타데이터터 + 댓글)
	@Bean
    public RedisTemplate<String, VideoAnalysisResponse> videoRedisTemplate(RedisConnectionFactory connectionFactory) {
		RedisTemplate<String, VideoAnalysisResponse> tpl = new RedisTemplate<>();
	    tpl.setConnectionFactory(connectionFactory);
	    var serializer = new GenericJackson2JsonRedisSerializer(); 
	    tpl.setKeySerializer(new StringRedisSerializer());
	    tpl.setValueSerializer(serializer);
	    tpl.setDefaultSerializer(serializer);
	    tpl.afterPropertiesSet();
	    return tpl;
    }
}
