package cn.travelbao;

import cn.travelbao.chat.service.ChatService;
import cn.travelbao.common.exception.ProblemMessages;
import cn.travelbao.common.web.IntegrationController;
import cn.travelbao.config.McpGateway;
import cn.travelbao.config.TravelSettings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;

class ChatSafetyTest {
    private TravelSettings settings(String key) {
        return new TravelSettings("https://model.test", key, "test-model", "http://127.0.0.1:11434",
                "test-embedding", "http://127.0.0.1:8081", "/mcp", 2);
    }

    @Test
    void missingKeyEmitsAnExplicitTerminalErrorWithoutCallingModel() {
        var service = new ChatService(null, settings(""), null, null, null);
        StepVerifier.create(service.stream("杭州旅游"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("error");
                    assertThat(event.code()).isEqualTo("MODEL_NOT_CONFIGURED");
                }).verifyComplete();
    }

    @Test
    void textualArithmeticDoesNotCountAsAnExecutedTool() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("总费用2800元"))));
        var properties = settings("test-only-key");
        var controller = new IntegrationController(properties, ChatClient.builder(model).build(), new McpGateway(properties,new com.fasterxml.jackson.databind.ObjectMapper()));
        var result = controller.probe("tools");
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).contains("没有实际调用");
    }

    @Test
    void providerBodyIsNotIncludedInAuthenticationError() {
        var error = new RestClientResponseException("upstream contains sensitive content", 401, "Unauthorized",
                HttpHeaders.EMPTY, "test-private-provider-body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        var problem = ProblemMessages.describe(error);
        assertThat(problem.code()).isEqualTo("MODEL_AUTH_FAILED");
        assertThat(problem.getMessage()).doesNotContain("test-private-provider-body", "sensitive");
    }

    @Test
    void springAiWrappedAuthenticationFailureKeepsAUsefulCodeWithoutProviderBody() {
        var problem = ProblemMessages.describe(new NonTransientAiException("HTTP 401 UNAUTHORIZED - test-private-provider-body"));
        assertThat(problem.code()).isEqualTo("MODEL_AUTH_FAILED");
        assertThat(problem.getMessage()).doesNotContain("test-private-provider-body");
    }
}
