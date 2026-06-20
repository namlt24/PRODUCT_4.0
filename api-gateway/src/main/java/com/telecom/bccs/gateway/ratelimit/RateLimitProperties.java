package com.telecom.bccs.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Giới hạn mặc định toàn hệ thống (fallback khi đối tác chưa cấu hình / DB không khả dụng)
 * và chu kỳ làm tươi cấu hình từ DB.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    /** TPS ổn định mặc định (tokens/giây). */
    private int defaultReplenishRate = 50;
    /** Sức chứa burst mặc định. */
    private int defaultBurstCapacity = 100;
    /** Số token tiêu thụ mỗi request. */
    private int defaultRequestedTokens = 1;
    /** Chu kỳ làm tươi cấu hình đối tác từ DB (ms). */
    private long refreshMs = 15000;

    public int getDefaultReplenishRate() { return defaultReplenishRate; }
    public void setDefaultReplenishRate(int v) { this.defaultReplenishRate = v; }

    public int getDefaultBurstCapacity() { return defaultBurstCapacity; }
    public void setDefaultBurstCapacity(int v) { this.defaultBurstCapacity = v; }

    public int getDefaultRequestedTokens() { return defaultRequestedTokens; }
    public void setDefaultRequestedTokens(int v) { this.defaultRequestedTokens = v; }

    public long getRefreshMs() { return refreshMs; }
    public void setRefreshMs(long v) { this.refreshMs = v; }
}
