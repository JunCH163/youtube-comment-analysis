package com.example.youtube_comment_analysis.video;

public enum Sentiment {
    POSITIVE, NEUTRAL, NEGATIVE;

    public static Sentiment fromPrediction(Integer p) {
        if (p == null) return NEUTRAL;  // 안전장치
        return switch (p) {
            case 2 -> POSITIVE;
            case 1 -> NEUTRAL;
            default -> NEGATIVE;
        };
    }
}
