package cn.travelbao.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import cn.travelbao.common.exception.ServiceProblem;
import com.fasterxml.jackson.databind.*;
import java.util.List;
import java.util.Set;

@Component
public class McpGateway {
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
