package com.example.youtube_comment_analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.youtube_comment_analysis.video.CommentDto;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AiSender {
	private final WebClient fastApiWebClient;
	
	public AiSender(@Qualifier("fastApiWebClient") WebClient fastApiWebClient) {
        this.fastApiWebClient = fastApiWebClient;
    }
	
	@Value("${fastapi.comment-analysis-path:/analyze/comments}")
    private String path;
	
	@Value("${fastapi.timeout-ms:20000}")
    private long timeoutMs;
	
	@Value("${fastapi.max-batch:500}")
    private int maxBatch;
	
	
	public SendResult send(List<CommentDto> comments) {
		if(comments==null || comments.isEmpty())
			return new SendResult(0,0,0);
		
		comments = comments.stream()
                .filter(c -> c.getText() != null && !c.getText().isBlank())
                .toList();
		
		if (comments.isEmpty()) 
			return new SendResult(0, 0, 0);
		
		List<List<CommentDto>> batches = chunk(comments, Math.max(1, maxBatch));
		String requestId = UUID.randomUUID().toString();
		
		int ok = 0, fail4xx = 0, failOther = 0;
		
		for(List<CommentDto> batch:batches) {
			String etag=sha256For(batch);
			var req=new AiSentimentRequest(batch.stream()
					.map(c->new AiSentimentRequest.Comment(c.getCommentId(), c.getAuthor(),c.getText(), c.getLikeCount(), c.getPublishedAt(),c.getPrediction()))
					.toList(), 
					new AiSentimentRequest.Trace(requestId, etag));
			try {
				var spec=fastApiWebClient.post()
						.uri(path)
						.bodyValue(req);
				
				ResponseEntity<Void> resp=spec.retrieve()
						.toBodilessEntity()
						.timeout(Duration.ofMillis(timeoutMs))
						.block();
				
				int code=resp!=null ? resp.getStatusCode().value() : -1;
				if(code>=200 && code<300) {
					ok+=batch.size();
					log.info("FastAPI send ok: batchSize={} etag={}", batch.size(), etag);
				}
				else if(code >= 400 && code < 500) {
					fail4xx += batch.size();
					log.warn("FastAPI client error {} etag={} batchSize={}", code, etag, batch.size());
				}
				else {
					failOther += batch.size();
					log.error("FastAPI non-2xx {} etag={} batchSize={}", code, etag, batch.size());
				}
			}
			catch(WebClientResponseException e) {
				fail4xx += batch.size();
				log.error("FastAPI HTTP {} {} body={}", e.getRawStatusCode(), e.getStatusText(), e.getResponseBodyAsString(), e);
			}
			catch(Exception e) {
				failOther += batch.size();
				log.error("FastAPI send failed etag={} batchSize={}", etag, batch.size(), e);
			}
		}
		return new SendResult(ok, fail4xx, failOther);
	}
	
	
	 public record SendResult(int success, int clientError, int otherError) {}
	 
	 private static List<List<CommentDto>> chunk(List<CommentDto> list, int size) {
	        List<List<CommentDto>> out = new ArrayList<>();
	        for (int i = 0; i < list.size(); i += size) {
	            out.add(list.subList(i, Math.min(i + size, list.size())));
	        }
	        return out;
	    }
	 
	 private static String sha256For(List<CommentDto> comments) {
	        try {
	            MessageDigest md = MessageDigest.getInstance("SHA-256");
	            String payload = comments.stream()
	            		.sorted(Comparator.comparing(CommentDto::getCommentId))
	            		.map(c -> c.getCommentId() + ":" + (c.getText() == null ? 0 : c.getText().length()))
	                    .collect(Collectors.joining("|"));
	            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
	            StringBuilder sb = new StringBuilder();
	            for (byte b : digest) sb.append(String.format("%02x", b));
	            return sb.toString();
	        } catch (Exception e) {
	            return UUID.randomUUID().toString();
	        }
	    }
}
