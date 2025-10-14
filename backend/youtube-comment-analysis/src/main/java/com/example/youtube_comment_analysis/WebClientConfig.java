package com.example.youtube_comment_analysis;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {
	@Bean
    public reactor.netty.resources.ConnectionProvider youtubePool() {
        return reactor.netty.resources.ConnectionProvider.builder("yt-pool")
                .maxConnections(200)
                .pendingAcquireMaxCount(1000)
                .pendingAcquireTimeout(Duration.ofSeconds(2))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
    }
	
	@Bean(name = "youtubeHttpClient")
    public HttpClient youtubeHttpClient(reactor.netty.resources.ConnectionProvider youtubePool) {
        return HttpClient.create(youtubePool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(5))
                            .addHandlerLast(new WriteTimeoutHandler(5)))
                .keepAlive(true);
    }
	
	@Bean(name = "youtubeWebClient")
    public WebClient youtubeWebClient(@org.springframework.beans.factory.annotation.Qualifier("youtubeHttpClient") HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl("https://www.googleapis.com/youtube/v3")
                .defaultHeader("Accept", "application/json")
                .build();
    }
	
	@Bean(name = "fastApiWebClient")
	public WebClient fastApiWebClient(
	        @Value("${fastapi.base-url}") String baseUrl,
	        @Value("${fastapi.connect-timeout-ms:5000}") int connectTimeoutMs,
	        @Value("${fastapi.response-timeout-ms:35000}") long responseTimeoutMs) {

	    HttpClient httpClient = HttpClient.create()
	        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
	        .responseTimeout(java.time.Duration.ofMillis(responseTimeoutMs))
	        .doOnConnected(conn -> conn
	            .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler((int)(responseTimeoutMs/1000)))
	            .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler((int)(responseTimeoutMs/1000))))
	        .keepAlive(true)
	        .compress(true);

	    return WebClient.builder()
	        .baseUrl(baseUrl)
	        .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
	        .defaultHeader("Accept", "application/json")
	        .defaultHeader("Content-Type", "application/json")
	        .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)) 
	        .build();
	}
}
