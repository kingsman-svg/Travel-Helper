package cn.travelbao.ai.agent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tourism")
public class TourismController {
    private final TourismGateway gateway;
    public TourismController(TourismGateway gateway){this.gateway=gateway;}
    @GetMapping("/tools") List<TourismGateway.Definition> tools(){return gateway.definitions();}
    @PostMapping("/{name}") JsonNode call(@PathVariable String name,@RequestBody Map<String,Object> arguments){return gateway.call(name,arguments);}
}
