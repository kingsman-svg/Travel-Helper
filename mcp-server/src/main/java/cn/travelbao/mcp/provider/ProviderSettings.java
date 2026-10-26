package cn.travelbao.mcp.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel.providers")
public record ProviderSettings(String geocodingUrl, String weatherUrl, String placesUrl, String routeUrl) {}
