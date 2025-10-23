package com.example.youtube_comment_analysis.video;

import java.util.*;

public class StatsDto {
    private final Map<Sentiment, Integer> totalBySentiment = new EnumMap<>(Sentiment.class);
    private final List<HourlyStat> hourly = new ArrayList<>();
    private final Map<Sentiment, CommentDto> topLikedBySentiment = new EnumMap<>(Sentiment.class);

    public StatsDto() {
        for (Sentiment s : Sentiment.values()) totalBySentiment.put(s, 0);
        for (int h = 0; h < 24; h += 2) hourly.add(new HourlyStat(h)); // 0,2,...,22
    }

    public Map<Sentiment, Integer> getTotalBySentiment() { return totalBySentiment; }
    public List<HourlyStat> getHourly() { return hourly; }
    public Map<Sentiment, CommentDto> getTopLikedBySentiment() { return topLikedBySentiment; }

    public void incTotal(Sentiment s) { totalBySentiment.put(s, totalBySentiment.get(s) + 1); }
}
