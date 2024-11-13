package com.github.matsik.messages;

import com.github.matsik.Message;
import com.github.matsik.MessageCreated;
import com.github.matsik.MessagePage;
import com.github.matsik.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService service;

    @PostMapping
    public ResponseEntity<MessageCreated> pushMessage(@RequestBody Message message) {
        Long offset = service.pushMessage(message);
        return ResponseEntity.ok(new MessageCreated(offset));
    }

    @GetMapping
    public ResponseEntity<MessagePage> getMessages(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit
    ) {
        Pagination pagination = new Pagination(offset, limit);

        List<Message> messages = service.getMessages(pagination);

        return ResponseEntity.ok(new MessagePage(messages));
    }
}
