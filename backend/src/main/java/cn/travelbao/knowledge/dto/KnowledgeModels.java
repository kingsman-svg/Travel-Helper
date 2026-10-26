package cn.travelbao.knowledge.dto;

import java.util.List;

public final class KnowledgeModels {
    private KnowledgeModels() {}
    public record DocumentView(String id, String city, String title, String sourceUrl, String verifiedAt,
                               String filename, String origin, String status, String errorMessage,
                               int chunkCount, String createdAt, String indexedAt, String contentHash) {}
    public record DocumentDetail(DocumentView document, String content, List<ChunkView> chunks) {}
    public record ChunkView(String id, int ordinal, String content) {}
    public record Citation(String documentId, String chunkId, String title, String city, String sourceUrl,
                           String verifiedAt, String origin, String excerpt, double score) {}
    public record SearchResult(String city, String query, String status, String message,
                               List<Citation> hits, long durationMs, String model, int dimensions) {}
    public record IndexState(String status, String message, String configuredModel, String indexedModel,
                             String modelDigest, Integer dimensions, int documentCount, int chunkCount,
                             String generation, String updatedAt, boolean busy) {}
    public record ImportResult(DocumentView document, boolean duplicate) {}
    public record CatalogItem(String id, String city, String title, String sourceUrl, String verifiedAt, String file) {}
    public record Answer(String answer, String status, List<Citation> citations, SearchResult retrieval) {}
}
