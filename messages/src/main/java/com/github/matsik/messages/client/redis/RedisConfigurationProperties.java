package com.github.matsik.messages.client.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("redis")
public record RedisConfigurationProperties(String host, int port, String username, char[] password) {
}
