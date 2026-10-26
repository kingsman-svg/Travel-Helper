package cn.travelbao.mcp.tools;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ServiceInfoTools {
    @Tool(name = "travel_service_info", description = "读取旅游宝 MCP 服务的当前版本与已实现能力，用于连接验证")
    public Map<String, Object> serviceInfo() {
        return Map.of("service", "travelbao-mcp", "version", "0.1.0", "stage", "mcp-connection",
                "implementedTools", List.of("travel_service_info"),
                "weatherAvailable", false, "placesAvailable", false, "routesAvailable", false,
                "checkedAt", Instant.now().toString());
    }
}
