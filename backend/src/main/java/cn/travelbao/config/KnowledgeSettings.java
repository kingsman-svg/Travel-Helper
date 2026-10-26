package cn.travelbao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel.knowledge")
public record KnowledgeSettings(int chunkSize, int chunkOverlap, int topK, double minScore,
                                int maxUploadBytes, int maxDocuments, String examplesPath) {}
