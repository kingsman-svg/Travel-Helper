package cn.travelbao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel")
public record TravelSettings(
        String deepseekBaseUrl, String deepseekApiKey, String model,
        String ollamaBaseUrl, String embeddingModel,
        String mcpBaseUrl, String mcpEndpoint, int requestTimeoutSeconds) {
    public boolean modelConfigured() {
        return deepseekApiKey != null && !deepseekApiKey.isBlank();
    }
}
