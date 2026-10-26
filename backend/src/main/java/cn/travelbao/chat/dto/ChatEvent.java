package cn.travelbao.chat.dto;

import java.time.Instant;

public record ChatEvent(String type, String executionId, String content, String code, Instant timestamp) {
    public static ChatEvent of(String type, String id, String content) {
        return new ChatEvent(type, id, content, null, Instant.now());
    }
    public static ChatEvent error(String id, String content, String code) {
        return new ChatEvent("error", id, content, code, Instant.now());
    }
}
