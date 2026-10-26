package cn.travelbao.mcp.config;

import cn.travelbao.mcp.tools.ServiceInfoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfiguration {
    @Bean
    ToolCallbackProvider serviceTools(ServiceInfoTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
