package com.streamforge.api.publication;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "streamforge.publication.enabled",
            havingValue = "true")
    static class PublisherScheduling {
    }
}
