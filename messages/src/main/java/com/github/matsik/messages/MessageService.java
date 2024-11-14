package com.github.matsik.messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.matsik.Message;
import com.github.matsik.Pagination;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MessageService {

    private static final String MESSAGE_LOG_KEY = "message-log";
    private static final long MESSAGE_LOG_MAX_SIZE = 1024;

    private final StatefulRedisConnection<String, String> redisConnection;
    private final ObjectMapper objectMapper;

    public MessageService(StatefulRedisConnection<String, String> redisConnection, ObjectMapper objectMapper) {
        this.redisConnection = redisConnection;
        this.objectMapper = objectMapper;
    }

    public Long pushMessage(Message message) {
        RedisCommands<String, String> commands = redisConnection.sync();

        String value = encodeMessage(message);

        Long size = commands.rpush(MESSAGE_LOG_KEY, value);
        commands.ltrim(MESSAGE_LOG_KEY, 0, MESSAGE_LOG_MAX_SIZE - 1);

        return getOffset(size);
    }

    private static Long getOffset(Long size) {
        return size - 1;
    }

    public List<Message> getMessages(Pagination pagination) {
        List<String> rawMessages = getRawMessages(pagination.offset(), pagination.limit());
        return decodeRawMessages(rawMessages);
    }

    private List<String> getRawMessages(int offset, int limit) {
        RedisCommands<String, String> commands = redisConnection.sync();

        int start = offset;
        int end = offset + limit - 1;
        return commands.lrange(MESSAGE_LOG_KEY, start, end);
    }

    private String encodeMessage(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    private List<Message> decodeRawMessages(List<String> rawMessages) {
        try {
            List<Message> messages = new ArrayList<>(rawMessages.size());
            for (String rawMessage : rawMessages) {
                Message message = objectMapper.readValue(rawMessage, Message.class);
                messages.add(message);
            }
            return messages;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex.getMessage());
        }
    }

}
