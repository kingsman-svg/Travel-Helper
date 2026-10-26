package cn.travelbao.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import cn.travelbao.ai.agent.TourismGateway;
import cn.travelbao.common.exception.ServiceProblem;
import com.fasterxml.jackson.databind.*;
import java.util.List;
import java.util.Set;

@Component
public class McpGateway implements TourismGateway {
    private final TravelSettings settings;
    private final ObjectMapper json;
    private static final Set<String> TOURISM=Set.of("weather_forecast","search_places","plan_route");
    private McpSyncClient client;
    private McpSchema.InitializeResult initialized;

    public McpGateway(TravelSettings settings,ObjectMapper json) { this.settings = settings;this.json=json; }

    private void connect() {
        if(client!=null)return;
        var transport=HttpClientStreamableHttpTransport.builder(settings.mcpBaseUrl()).endpoint(settings.mcpEndpoint()).build();
        client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(25)).build();initialized=client.initialize();
    }
    public synchronized List<TourismGateway.Definition> definitions() {
        try {
            connect();var result=new java.util.ArrayList<TourismGateway.Definition>();
            for(var tool:client.listTools().tools())if(TOURISM.contains(tool.name()))result.add(new TourismGateway.Definition(tool.name(),tool.description(),json.writeValueAsString(tool.inputSchema())));
            if(result.size()!=3)throw new IllegalStateException();return List.copyOf(result);
        }catch(Exception e){close();throw new ServiceProblem("MCP_UNAVAILABLE","旅游 MCP 工具不可用，请检查本机 MCP 服务。");}
    }
    public synchronized JsonNode call(String name,Map<String,Object> arguments) {
        if(!TOURISM.contains(name))throw new ServiceProblem("INVALID_TOOL","不支持该工具。");
        try {
            connect();var response=client.callTool(new McpSchema.CallToolRequest(name,arguments));
            if(Boolean.TRUE.equals(response.isError()))throw new IllegalStateException();
            if(response.structuredContent()!=null){var value=json.valueToTree(response.structuredContent());if(value.has("status"))return value;if(value.path("result").has("status"))return value.path("result");}
            for(var content:response.content())if(content instanceof McpSchema.TextContent text&&text.text().length()<60000){var value=json.readTree(text.text());if(value.has("status"))return value;}
            throw new IllegalStateException();
        }catch(Exception e){close();throw new ServiceProblem("MCP_UNAVAILABLE","旅游工具调用失败，未获得有效结果，请稍后重试。");}
    }

    public synchronized Map<String, Object> probe() {
        try {
            connect();
            var tools = client.listTools().tools();
            boolean present = tools.stream().anyMatch(tool -> tool.name().equals("travel_service_info"));
            if (!present) throw new IllegalStateException("Missing service information tool");
            var result = client.callTool(new McpSchema.CallToolRequest("travel_service_info", Map.of()));
            if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException("MCP tool failed");
            return Map.of("server", initialized.serverInfo().name(), "protocolVersion", initialized.protocolVersion(),
                    "tools", tools.stream().map(McpSchema.Tool::name).toList(), "result", result.content());
        }
        catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            try { client.close(); } catch (RuntimeException ignored) { }
        }
        client = null;
        initialized = null;
    }
}
