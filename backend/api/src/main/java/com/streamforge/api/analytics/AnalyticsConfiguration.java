package com.streamforge.api.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsRebuildProperties.class)
class AnalyticsConfiguration {
}
