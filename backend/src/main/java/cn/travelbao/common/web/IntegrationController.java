package cn.travelbao.common.web;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class IntegrationController {
 @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","application","travelbao-backend","stage","scaffold");}
}
