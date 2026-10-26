package cn.travelbao.ai.rag;

import java.util.List;

public interface KnowledgeEmbedder {
    record ModelIdentity(String model, String digest) {}
    record Embeddings(ModelIdentity identity, List<float[]> vectors, int dimensions) {}
    ModelIdentity identity();
    Embeddings embed(List<String> texts);
}
