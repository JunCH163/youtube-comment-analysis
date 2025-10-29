package com.example.youtube_comment_analysis.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	
	//ai서버에 댓글 전송 함수
	public SendResult send(List<CommentDto> allComments) {
		if (allComments == null || allComments.isEmpty()) {
            return new SendResult(List.of(), List.of(), 0,0,0);
        }
			
		List<List<CommentDto>> batches = chunk(allComments, Math.max(1, maxBatch));
		
	    Map<String, Integer> globalKeyword=new HashMap<>(256);
	    Set<String> seenIds=new HashSet<>();
	    List<CommentDto> keptAll=new ArrayList<>();
		
		String requestId = UUID.randomUUID().toString();
		
		int ok = 0, fail4xx = 0, failOther = 0;
		
		for(int b = 0;b<batches.size();b++) {
			List<CommentDto> batch = batches.get(b);
			int batchNo = b + 1;
			int totalBatches = batches.size();
			String etag=sha256For(batch);
			
			var req=new AiSentimentRequest(batch.stream()
					.map(c->new AiSentimentRequest.Comment(c.getCommentId(), c.getAuthor(),c.getText(), c.getLikeCount(), c.getPublishedAt(),c.getPrediction()))
					.toList(), 
					new AiSentimentRequest.Trace(requestId, etag));
			
			long startNs = System.nanoTime();
			
			try {
				log.info("AI서버로 전송 reqId={} batchNo={}/{} size={} etag={}",
	                    requestId, batchNo, totalBatches, batch.size(), etag);
				
				ResponseEntity<AiSentimentResponse> resp = fastApiWebClient.post()
		                .uri(path)
		                .bodyValue(req)
		                .retrieve()
		                .toEntity(AiSentimentResponse.class)
		                //.timeout(Duration.ofMillis(timeoutMs))
		                .block();
				
				int code=resp!=null ? resp.getStatusCode().value() : -1;
				
				if(code>=200 && code<300) {
					ok+=batch.size();
					log.info("AI서버로 전송 성공: batchSize={} etag={}", batch.size(), etag);
					
					if (resp == null || resp.getBody() == null) 
						continue;
					
	                AiSentimentResponse body = resp.getBody();
	                
	                if (body.topKeyword() != null) {
	                    for (KeywordCount kc : body.topKeyword()) {
	                        if (kc == null || kc.keyword() == null) 
	                        	continue;
	                        String key = kc.keyword().trim();
	                        int add = Math.max(0, kc.count());
	                        globalKeyword.merge(key, add, Integer::sum);
	                    }
	                }
	                
	                List<CommentDto> analyzed = (body.comments() != null) ? body.comments() : List.of();  
	                Set<String> keepIds = new HashSet<>(Math.max(16, analyzed.size() * 2));
	                Map<String, Integer> id2pred = new HashMap<>(Math.max(16, analyzed.size() * 2));
	                
	                for (CommentDto c : analyzed) {
	                    if (c == null) 
	                    	continue;
	                    String id = c.getCommentId();
	                    if (id != null) {
	                        keepIds.add(id);
	                        if (c.getPrediction() != null) 
	                        	id2pred.put(id, c.getPrediction());
	                    }
	                }
	                
	                int updated = 0, unmatched = 0, missingId = 0;
	                for (CommentDto orig : batch) {
	                    if (orig == null) 
	                    	continue;
	                    String id = orig.getCommentId();
	                    if (id == null) {
	                    	missingId++; 
	                    	continue;
	                    }
	                    if (keepIds.contains(id) && seenIds.add(id)) {
	                        Integer p = id2pred.get(id);
	                        if (p != null) {
	                        	orig.setPrediction(p);
	                        	updated++;
	                        }
	                        	
	                        keptAll.add(orig);
	                    }
	                    else {
	                    	unmatched++;
	                    }
	                }
	                log.info("AI apply: updated={}, unmatched(no-returned)={}, missingId={}",
                            updated, unmatched, missingId);
				}
				else if(code >= 400 && code < 500) {
					fail4xx += batch.size();
					log.warn("FastAPI 클라이언트 오류 {} etag={} batchSize={}", code, etag, batch.size());
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
		
		long predicted = allComments.stream().filter(c -> c.getPrediction()!=null).count();
		log.info("분류 완료 reqId={} total={} predicted={} ok={} 4xx={} other={}",
	            requestId, allComments.size(), predicted, ok, fail4xx, failOther);
		
		//댓글 집계
		List<CommentDto> topLikedFlattened=getGlobalComments(keptAll);
		
		//키워드 집계
	    List<KeywordCount> topKeywordGlobal=getGlobalKeyword(globalKeyword, 3);
	    
	    //감정 비율 집개
	    int pos = 0, neu = 0, neg = 0;
	    for (CommentDto c : keptAll) {
	        Integer p = c.getPrediction();
	        if (p == null) 
	        	continue;
	        if (p == 2) 
	        	pos++;
	        else if (p == 1) 
	        	neu++;
	        else if (p == 0) 
	        	neg++;
	    }
		
	    return new SendResult(topLikedFlattened, topKeywordGlobal, pos, neu, neg);
	}
	 
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
	 
	 
	 //키워드 집계 함수
	 public static List<KeywordCount> getGlobalKeyword(Map<String, Integer> globalKeyword,int top_N){
		 List<KeywordCount> topKeywordGlobal = globalKeyword.entrySet().stream()
				 .sorted(Map.Entry.<String,Integer>comparingByValue(Comparator.reverseOrder())
				         .thenComparing(Map.Entry::getKey))
				 .limit(top_N)
				 .map(e -> new KeywordCount(e.getKey(), e.getValue()))
				 .toList();
		 return topKeywordGlobal;
	 }
	 
	 //댓글 집계
	 public static List<CommentDto> getGlobalComments(List<CommentDto> allComments){
		 Comparator<CommentDto> byLikeDescThenTimeThenId =
				 Comparator.comparingLong((CommentDto c) -> c.getLikeCount() == null ? 0L : c.getLikeCount())
				 	.reversed()
				 	.thenComparing(CommentDto::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				 	.thenComparing(CommentDto::getCommentId, Comparator.nullsLast(Comparator.naturalOrder()));

		 Map<Integer, List<CommentDto>> topLiked =
				 allComments.stream()
				 .filter(c -> c != null && c.getPrediction() != null)
				 .collect(Collectors.groupingBy(
						 CommentDto::getPrediction,
						 Collectors.collectingAndThen(
								 Collectors.toList(),
								 list -> list.stream().sorted(byLikeDescThenTimeThenId).limit(10).toList()
								 )
						 ));
		 List<CommentDto> topLikedFlattened =
				    java.util.stream.Stream.of(0, 1, 2)
				        .flatMap(p -> topLiked.getOrDefault(p, List.of()).stream())
				        .toList();
		 
		 return topLikedFlattened;
	 }
}
