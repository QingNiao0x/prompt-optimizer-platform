package com.promptoptimizer.trial;

/**
 * 每个访客身份的一次性体验总额，非每日配额；三种功能相互独立。
 * @author QingNiao
 * @since 0.1.0
 */
public enum TrialFeature {
    IMAGE_PROMPT("imagePrompt", 1), ENHANCEMENT("enhancement", 3), TEXT_POLISH("textPolish", 3);
    private final String key;
    private final int limit;
    TrialFeature(String key, int limit) { this.key = key; this.limit = limit; }
    public String key() { return key; }
    public int limit() { return limit; }
}
