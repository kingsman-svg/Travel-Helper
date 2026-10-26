package cn.travelbao.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "请先输入旅行需求")
        @Size(max = 4000, message = "旅行需求请控制在 4000 字以内") String message,
        @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F-]{36}") String conversationId) {
}
