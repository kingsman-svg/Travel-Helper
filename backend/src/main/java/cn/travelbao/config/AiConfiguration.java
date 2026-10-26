package cn.travelbao.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfiguration {
    @Bean
    ChatClient travelChatClient(TravelSettings settings, ChatMemory memory) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(settings.requestTimeoutSeconds()));
        return configuredClient(settings, RestClient.builder().requestFactory(factory)).mutate()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(10).build();
    }

    ChatClient configuredClient(TravelSettings settings, RestClient.Builder restClient) {
        // DeepSeek's compatible endpoint supports extra parameters omitted by the 1.1 native adapter.
        var api = OpenAiApi.builder()
                .baseUrl(settings.deepseekBaseUrl())
                .completionsPath("/chat/completions")
                .apiKey(settings.modelConfigured() ? settings.deepseekApiKey() : "not-configured")
                .restClientBuilder(restClient)
                .build();
        var options = OpenAiChatOptions.builder()
                .model(settings.model())
                .maxTokens(7000)
                .temperature(0.5)
                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                .build();
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        return ChatClient.builder(model).build();
    }
}
