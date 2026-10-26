package cn.travelbao.ai.agent;
import cn.travelbao.ai.agent.AgentModels.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
@RestController
@RequestMapping("/api")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service){this.service=service;}
    @PostMapping(value="/agent/runs/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) Flux<Event> stream(@Valid @RequestBody Request body){return service.stream(body);}
    @PostMapping("/agent/runs/{id}/cancel") Run cancel(@PathVariable String id){return service.cancel(id);}
    @GetMapping("/agent/runs/{id}") Run get(@PathVariable String id){return service.get(id);}
    @GetMapping("/conversations/{id}/agent-runs/latest") ResponseEntity<Run> latest(@PathVariable String id){return service.latest(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());}
}
