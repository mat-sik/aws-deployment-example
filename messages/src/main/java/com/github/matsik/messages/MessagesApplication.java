package com.github.matsik.messages;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MessagesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessagesApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(StatefulRedisConnection<String, String> connection) {
        return _ -> {
            RedisCommands<String, String> commands = connection.sync();

            commands.set("foo", "bar", SetArgs.Builder.ex(Duration.ofSeconds(10)));
            String result = commands.get("foo");
            System.out.println(result);

            connection.close();
        };
    }

}
