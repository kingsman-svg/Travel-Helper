package cn.travelbao.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("travel.agent")
public record AgentSettings(int maxSteps,int maxToolCalls,int timeoutSeconds) {
    public AgentSettings {
        if(maxSteps<1||maxSteps>6||maxToolCalls<1||maxToolCalls>10||timeoutSeconds<1||timeoutSeconds>90)throw new IllegalArgumentException("智能体限制必须在1–6轮、1–10次工具和1–90秒范围内");
    }
}
