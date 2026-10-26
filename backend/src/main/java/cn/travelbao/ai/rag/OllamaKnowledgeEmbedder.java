package cn.travelbao.ai.rag;

import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.config.TravelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaKnowledgeEmbedder implements KnowledgeEmbedder {
    private final TravelSettings settings;
    private final RestClient client;
    public OllamaKnowledgeEmbedder(TravelSettings settings) {
        this.settings = settings;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5)); factory.setReadTimeout(Duration.ofSeconds(90));
        client = RestClient.builder().baseUrl(settings.ollamaBaseUrl()).requestFactory(factory).build();
    }
    public ModelIdentity identity() {
        try {
            JsonNode body = client.get().uri("/api/tags").retrieve().body(JsonNode.class);
            if (body != null) for (JsonNode item : body.path("models")) {
                String name = item.path("name").asText();
                if ((name.equals(settings.embeddingModel()) || name.equals(settings.embeddingModel()+":latest"))
                        && !item.path("digest").asText().isBlank()) return new ModelIdentity(settings.embeddingModel(),item.path("digest").asText());
            }
            throw unavailable();
        } catch (Exception e) { throw unavailable(); }
    }
    public Embeddings embed(List<String> texts) {
        ModelIdentity identity = identity();
        try {
            JsonNode body = client.post().uri("/api/embed")
                    .body(Map.of("model",settings.embeddingModel(),"input",texts,"truncate",false,"keep_alive","10m"))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("embeddings").isArray() || body.path("embeddings").size() != texts.size()) throw unavailable();
            var vectors = new ArrayList<float[]>(); int dimensions = 0;
            for (JsonNode values : body.path("embeddings")) {
                if (!values.isArray() || values.isEmpty() || values.size() > 65536) throw unavailable();
                if (dimensions != 0 && dimensions != values.size()) throw unavailable();
                dimensions = values.size(); float[] vector = new float[dimensions]; double norm = 0;
                for (int i=0; i<dimensions; i++) {
                    if (!values.get(i).isNumber() || !Double.isFinite(values.get(i).doubleValue())) throw unavailable();
                    vector[i] = values.get(i).floatValue(); norm += (double)vector[i]*vector[i];
                }
                if (!Double.isFinite(norm) || norm <= 0) throw unavailable();
                double scale = Math.sqrt(norm);
                for (int i=0; i<dimensions; i++) vector[i] /= (float)scale;
                vectors.add(vector);
            }
            return new Embeddings(identity,List.copyOf(vectors),dimensions);
        } catch (Exception e) { throw unavailable(); }
    }
    private static ServiceProblem unavailable() { return new ServiceProblem("KNOWLEDGE_UNAVAILABLE","本地向量服务不可用，请确认 Ollama 与配置的模型已就绪。"); }
}
