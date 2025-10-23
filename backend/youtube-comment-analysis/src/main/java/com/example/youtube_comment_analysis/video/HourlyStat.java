package com.example.youtube_comment_analysis.video;

import java.util.EnumMap;
import java.util.Map;

public class HourlyStat {
    private final int hour; // 0,2,4,...,22
    private final Map<Sentiment, Integer> counts = new EnumMap<>(Sentiment.class);

    public HourlyStat(int hour) {
        this.hour = hour;
        for (Sentiment s : Sentiment.values()) counts.put(s, 0);
    }

    public int getHour() { return hour; }
    public Map<Sentiment, Integer> getCounts() { return counts; }
    public void inc(Sentiment s) { counts.put(s, counts.get(s) + 1); }
}
