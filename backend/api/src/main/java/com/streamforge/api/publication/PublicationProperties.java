package com.streamforge.api.publication;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "streamforge.publication")
public class PublicationProperties {

    private boolean enabled;
    private int maxPerCycle = 20;
    private long pollDelayMs = 1000;
    private String topic = "telemetry.reading.v1";
    private Duration sendTimeout = Duration.ofSeconds(10);
    private Duration lease = Duration.ofSeconds(30);
    private Duration retryBase = Duration.ofSeconds(1);
    private Duration retryMax = Duration.ofMinutes(5);
    private double jitterFraction = 0.2;
    private int attemptWarningInterval = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPerCycle() {
        return maxPerCycle;
    }

    public void setMaxPerCycle(int maxPerCycle) {
        this.maxPerCycle = maxPerCycle;
    }

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = pollDelayMs;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public Duration getRetryBase() {
        return retryBase;
    }

    public void setRetryBase(Duration retryBase) {
        this.retryBase = retryBase;
    }

    public Duration getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Duration retryMax) {
        this.retryMax = retryMax;
    }

    public double getJitterFraction() {
        return jitterFraction;
    }

    public void setJitterFraction(double jitterFraction) {
        this.jitterFraction = jitterFraction;
    }

    public int getAttemptWarningInterval() {
        return attemptWarningInterval;
    }

    public void setAttemptWarningInterval(int attemptWarningInterval) {
        this.attemptWarningInterval = attemptWarningInterval;
    }
}
