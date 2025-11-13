package com.example.youtube_comment_analysis.video;

import java.util.EnumMap;
import java.util.Map;

public class HourlyStat {
    private int hour; // 0,2,4,...,22
    private Map<Sentiment, Integer> counts = new EnumMap<>(Sentiment.class);
    
    public HourlyStat() {
        // Jackson 역직렬화용 기본 생성자
    }
    
    public HourlyStat(int hour) {
        this.hour = hour;
        initCounts();
    }

    private void initCounts() {
        this.counts = new EnumMap<>(Sentiment.class);
        for (Sentiment s : Sentiment.values()) {
            counts.put(s, 0);
        }
    }

    public int getHour() { return hour; }
    public void setHour(int hour) { this.hour = hour; }

    public Map<Sentiment, Integer> getCounts() { return counts; }
    public void setCounts(Map<Sentiment, Integer> counts) {
        this.counts = counts;
        // 혹시 누락된 감정이 있으면 0으로 채우기
        if (this.counts != null) {
            for (Sentiment s : Sentiment.values()) {
                this.counts.putIfAbsent(s, 0);
            }
        }
    }
    
    public void inc(Sentiment s) { counts.put(s, counts.get(s) + 1); }
}
