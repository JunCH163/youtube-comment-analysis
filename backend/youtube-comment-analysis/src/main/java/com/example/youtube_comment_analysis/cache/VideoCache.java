package com.example.youtube_comment_analysis.cache;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.example.youtube_comment_analysis.ai.AiSender;
import com.example.youtube_comment_analysis.video.VideoAnalysisResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoCache {
	private final RedisTemplate<String, VideoAnalysisResponse> videoTpl;
	
	private static final String VERSION = "v1";
	
	private static String key(String videoId) {
		return "l2:video:%s:%s".formatted(videoId,VERSION);
	}
	private static String lockKey(String videoId) {
		return key(videoId) + ":lock";
	}
	
	private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(80);
    
    private static final long WAIT_SLICE_MS = 120;   
    private static final long WAIT_MAX_MS   = 20000; 
	
    public VideoAnalysisResponse getOrLoadVideoData(String videoId, Supplier<VideoAnalysisResponse> loader) {
    	final String k=key(videoId);
    	//저장된 캐시가 있으면 결과 반환
    	VideoAnalysisResponse hit=videoTpl.opsForValue().get(k);
    	if(hit!=null)
    		return hit;
    	//캐시 없으면 일단 redis에 생성하고 lock걸어서 처리 시작(중복 연산 방지)
    	final String lk=lockKey(videoId);
    	Boolean acquired=videoTpl.opsForValue()	.setIfAbsent(
    			lk,
    			new VideoAnalysisResponse(null,null,null,null,0,0,0,0,0),
    			LOCK_TTL);	
    	
    	if(Boolean.TRUE.equals(acquired)) {
    		try {
    			hit=videoTpl.opsForValue().get(k);
    			if(hit!=null)
    				return hit;
    			
    			log.info("캐싱 미스");
    			VideoAnalysisResponse fresh=loader.get();
    			
    			videoTpl.opsForValue().set(k, fresh,DEFAULT_TTL);
    			return fresh;
    		}
    		finally {
    			videoTpl.delete(lk);
    		}
    	}
    	else {
    		//다른 스레드에서 같은 id 요청 시
    		long waited = 0L;
            while (waited < WAIT_MAX_MS) {
                try { 
                	Thread.sleep(WAIT_SLICE_MS); 
                	} 
                catch (InterruptedException ignored) {}
                waited += WAIT_SLICE_MS;

                // 캐시 채워졌는지 먼저 확인
                hit = videoTpl.opsForValue().get(k);
                if (hit != null) 
                	return hit;

                // 락이 사라졌으면 내가 다시 시도
                if (Boolean.FALSE.equals(videoTpl.hasKey(lk))) {
                    Boolean reacquired = videoTpl.opsForValue().setIfAbsent(
                            lk,
                            new VideoAnalysisResponse(null, null, null, null, 0, 0, 0, 0, 0),
                            LOCK_TTL);
                    if (Boolean.TRUE.equals(reacquired)) {
                        try {
                            hit = videoTpl.opsForValue().get(k);
                            if (hit != null) 
                            	return hit;

                            VideoAnalysisResponse fresh = loader.get();
                            videoTpl.opsForValue().set(k, fresh, DEFAULT_TTL);
                            return fresh;
                        } 
                        finally {
                            videoTpl.delete(lk);
                        }
                    }
                }
            }
    		hit=videoTpl.opsForValue().get(k);
    		if(hit!=null)
    			return hit;
    		
    		VideoAnalysisResponse fresh = loader.get();
            videoTpl.opsForValue().set(k, fresh, DEFAULT_TTL);
            return fresh;
    	}
    }
    
    public void invalidate(String videoId) {
        videoTpl.delete(key(videoId));
    }
}
