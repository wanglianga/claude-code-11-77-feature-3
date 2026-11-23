package com.example.clearing.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clearing")
public class ClearingProperties {

    /** 待遇规则版本（登记时写入任务依赖版本快照） */
    private String ruleVersion = "2026.11";

    private Scheduler scheduler = new Scheduler();
    private Backoff backoff = new Backoff();
    private Storm storm = new Storm();
    private Coordinator coordinator = new Coordinator();

    /** 依赖本地令牌桶配置：dependencyCode -> 桶规格 */
    private Map<String, BucketSpec> buckets = new LinkedHashMap<>();

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    public void setBackoff(Backoff backoff) {
        this.backoff = backoff;
    }

    public Storm getStorm() {
        return storm;
    }

    public void setStorm(Storm storm) {
        this.storm = storm;
    }

    public Coordinator getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    public Map<String, BucketSpec> getBuckets() {
        return buckets;
    }

    public void setBuckets(Map<String, BucketSpec> buckets) {
        this.buckets = buckets;
    }

    public static class Scheduler {
        /** 是否开启自动调度（默认关闭，验收通过手动 tick 驱动，保证确定性） */
        private boolean autoEnabled = false;
        private long intervalMs = 3000;

        public boolean isAutoEnabled() {
            return autoEnabled;
        }

        public void setAutoEnabled(boolean autoEnabled) {
            this.autoEnabled = autoEnabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Backoff {
        private long baseMs = 1000;
        private long maxMs = 10000;
        private int maxAttempts = 5;

        public long getBaseMs() {
            return baseMs;
        }

        public void setBaseMs(long baseMs) {
            this.baseMs = baseMs;
        }

        public long getMaxMs() {
            return maxMs;
        }

        public void setMaxMs(long maxMs) {
            this.maxMs = maxMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Storm {
        private int windowSeconds = 300;
        private int failureStormThreshold = 5;
        private int failureWatchThreshold = 3;
        private int queueGrowthWatchThreshold = 5;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getFailureStormThreshold() {
            return failureStormThreshold;
        }

        public void setFailureStormThreshold(int failureStormThreshold) {
            this.failureStormThreshold = failureStormThreshold;
        }

        public int getFailureWatchThreshold() {
            return failureWatchThreshold;
        }

        public void setFailureWatchThreshold(int failureWatchThreshold) {
            this.failureWatchThreshold = failureWatchThreshold;
        }

        public int getQueueGrowthWatchThreshold() {
            return queueGrowthWatchThreshold;
        }

        public void setQueueGrowthWatchThreshold(int queueGrowthWatchThreshold) {
            this.queueGrowthWatchThreshold = queueGrowthWatchThreshold;
        }
    }

    public static class Coordinator {
        /** 一个配额周期包含的调度周期数（用于把周期配额折算成每周期补充速率） */
        private int ticksPerPeriod = 10;

        public int getTicksPerPeriod() {
            return ticksPerPeriod;
        }

        public void setTicksPerPeriod(int ticksPerPeriod) {
            this.ticksPerPeriod = ticksPerPeriod;
        }
    }

    public static class BucketSpec {
        private int capacity;
        private int refillPerTick;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerTick() {
            return refillPerTick;
        }

        public void setRefillPerTick(int refillPerTick) {
            this.refillPerTick = refillPerTick;
        }
    }
}
