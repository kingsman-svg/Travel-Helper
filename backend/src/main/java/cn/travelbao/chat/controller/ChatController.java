package cn.travelbao.chat.controller;

import cn.travelbao.chat.dto.ChatEvent;
import cn.travelbao.chat.dto.ChatRequest;
import cn.travelbao.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> chat(@Valid @RequestBody ChatRequest request) {
        return chat.stream(request).map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }
}
