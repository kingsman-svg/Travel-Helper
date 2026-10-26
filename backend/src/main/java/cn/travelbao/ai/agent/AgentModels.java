package cn.travelbao.ai.agent;
import cn.travelbao.trip.dto.TravelRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class AgentModels {
    private AgentModels() {}
    public record Request(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String executionId,
                          @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String conversationId,@Min(0) int expectedVersion,
                          @Valid TravelRequest request,@Size(max=2000) String instruction) {}
    public record Event(long sequence,String type,String executionId,Object data,Instant timestamp) {}
    public record Run(String id,String conversationId,String status,String code,String message,String startedAt,String finishedAt,List<Event> events) {}
    public record Completion(String preparedPlanId,String summary) {}
}
