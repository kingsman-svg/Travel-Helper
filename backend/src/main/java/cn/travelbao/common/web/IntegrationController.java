package cn.travelbao.common.web;

import cn.travelbao.ai.tools.BudgetProbeTool;
import cn.travelbao.common.exception.ProblemMessages;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.config.McpGateway;
import cn.travelbao.config.TravelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
public class IntegrationController {
    private final TravelSettings settings;
    private final ChatClient chat;
    private final McpGateway mcp;
    private final RestClient ollama;
    private final Map<String, ProbeResult> results = new ConcurrentHashMap<>();

    public IntegrationController(TravelSettings settings, ChatClient chat, McpGateway mcp) {
        this.settings = settings;
        this.chat = chat;
        this.mcp = mcp;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.ollama = RestClient.builder().baseUrl(settings.ollamaBaseUrl()).requestFactory(factory).build();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "application", "travelbao-backend", "version", "0.4.0", "stage", "milestone-04");
    }

    @GetMapping("/integrations")
    public Map<String, Object> integrations() {
        var integrations = new LinkedHashMap<String, Object>();
        integrations.put("deepseek", status("chat", settings.modelConfigured(), settings.model()));
        integrations.put("tools", status("tools", settings.modelConfigured(), "模型工具调用"));
        integrations.put("mcp", status("mcp", true, "旅游 MCP 服务"));
        integrations.put("embedding", status("embedding", true, settings.embeddingModel()));
        return Map.of("integrations", integrations, "stage", "milestone-04");
    }

    private Object status(String name, boolean configured, String model) {
        return Map.of("configured", configured, "label", model,
                "status", results.containsKey(name) ? results.get(name).status() : configured ? "unverified" : "unconfigured",
                "lastProbe", results.containsKey(name) ? results.get(name) : Map.of());
    }

    @PostMapping("/probes/{type:chat|tools|mcp|embedding}")
    public ProbeResult probe(@PathVariable String type) {
        long start = System.nanoTime();
        ProbeResult result;
        if ((type.equals("chat") || type.equals("tools")) && !settings.modelConfigured()) {
            result = new ProbeResult(type, "unconfigured", "请在本机 .env 填写 DeepSeek 密钥后重启后端。", Map.of(), 0, Instant.now());
        }
        else {
            try {
                Map<String, Object> details = switch (type) {
                    case "chat" -> chatProbe();
                    case "tools" -> toolProbe();
                    case "mcp" -> mcp.probe();
                    case "embedding" -> embeddingProbe();
                    default -> throw new IllegalArgumentException("Unknown probe");
                };
                result = new ProbeResult(type, "passed", "验证通过", details, elapsed(start), Instant.now());
            }
            catch (Exception error) {
                String message = type.equals("mcp") ? "MCP 服务连接或工具调用失败，请检查服务状态。"
                        : type.equals("embedding") ? "向量服务不可用，请确认 Ollama 已启动并已下载配置的模型。"
                        : ProblemMessages.describe(error).getMessage();
                result = new ProbeResult(type, "failed", message, Map.of(), elapsed(start), Instant.now());
            }
        }
        results.put(type, result);
        return result;
    }

    private Map<String, Object> toolProbe() {
        var tool = new BudgetProbeTool();
        String reply = chat.prompt().system("你正在验证工具调用。必须调用 calculate_budget 计算，拿到工具结果后再用一句中文返回总额。")
                .user("住宿1500元、餐饮800元、交通500元，请调用工具计算总费用。")
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, java.util.UUID.randomUUID().toString()))
                .tools(tool).call().content();
        if (tool.calls() == 0) {
            throw new ServiceProblem("TOOL_NOT_CALLED", "模型返回了文字，但没有实际调用预算工具，验证未通过。");
        }
        if (tool.lastResult().accommodation() != 1500 || tool.lastResult().food() != 800
                || tool.lastResult().transport() != 500 || tool.lastResult().total() != 2800) {
            throw new ServiceProblem("TOOL_RESULT_MISMATCH", "模型调用的工具参数或计算结果与验证任务不一致。");
        }
        return Map.of("tool", "calculate_budget", "calls", tool.calls(), "result", tool.lastResult(), "reply", reply);
    }

    private Map<String, Object> chatProbe() {
        String reply = chat.prompt().user("请仅回复：旅游宝模型连接成功。不要附加其他内容。")
                .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, java.util.UUID.randomUUID().toString()))
                .call().content();
        if (reply == null || reply.isBlank()) throw new ServiceProblem("EMPTY_MODEL_REPLY", "模型没有返回有效内容。");
        return Map.of("model", settings.model(), "reply", reply);
    }

    private Map<String, Object> embeddingProbe() {
        JsonNode response = ollama.post().uri("/api/embed")
                .body(Map.of("model", settings.embeddingModel(), "input", "杭州适合轻松步行的旅游路线", "keep_alive", "10m"))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.path("embeddings").isArray() || response.path("embeddings").isEmpty()) {
            throw new IllegalStateException("No embedding returned");
        }
        JsonNode vector = response.path("embeddings").get(0);
        if (!vector.isArray() || vector.isEmpty()) throw new IllegalStateException("Empty vector");
        double norm = 0;
        for (JsonNode value : vector) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalStateException("Invalid vector");
            norm += value.doubleValue() * value.doubleValue();
        }
        if (norm == 0) throw new IllegalStateException("Zero vector");
        return Map.of("model", settings.embeddingModel(), "dimensions", vector.size(), "norm", Math.sqrt(norm));
    }

    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    public record ProbeResult(String type, String status, String message, Map<String, Object> details, long durationMs, Instant checkedAt) { }
}
