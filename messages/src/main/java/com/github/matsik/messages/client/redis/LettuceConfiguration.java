package com.github.matsik.messages.client.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LettuceConfiguration {

    @Bean
    public StatefulRedisConnection<String, String> statefulRedisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public RedisClient redisClient(RedisConfigurationProperties configurationProperties) {
        RedisURI uri = getRedisUri(configurationProperties);
        return RedisClient.create(uri);
    }

    private static RedisURI getRedisUri(RedisConfigurationProperties configurationProperties) {
        return RedisURI.builder()
                .withHost(configurationProperties.host())
                .withPort(configurationProperties.port())
                .withAuthentication(configurationProperties.username(), configurationProperties.password())
                .build();
    }

}
