package cn.travelbao.knowledge.controller;

import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.knowledge.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final KnowledgeAnswerService answers;
    public KnowledgeController(KnowledgeService knowledge,KnowledgeAnswerService answers) { this.knowledge=knowledge; this.answers=answers; }
    public record Search(@NotBlank @Size(max=60) String city,@NotBlank @Size(max=2000) String query) {}
    public record Ask(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String conversationId,
                      @NotBlank @Size(max=60) String city,@NotBlank @Size(max=2000) String message) {}
    @GetMapping("/documents") List<DocumentView> list() { return knowledge.list(); }
    @GetMapping("/documents/{id}") DocumentDetail get(@PathVariable String id) { return knowledge.document(id); }
    @GetMapping("/status") IndexState state() { return knowledge.state(); }
    @PostMapping("/documents") ImportResult upload(@RequestParam String city,@RequestParam String title,@RequestParam String sourceUrl,
                                                   @RequestParam String verifiedAt,@RequestParam MultipartFile file) throws IOException {
        return knowledge.upload(city,title,sourceUrl,verifiedAt,file.getOriginalFilename(),file.getBytes());
    }
    @PostMapping("/examples/import") List<ImportResult> examples() { return knowledge.importExamples(); }
    @PostMapping("/documents/{id}/index") ImportResult index(@PathVariable String id) { return knowledge.retry(id); }
    @PostMapping("/rebuild") IndexState rebuild() { return knowledge.rebuild(); }
    @PostMapping("/search") SearchResult search(@Valid @RequestBody Search body) { return knowledge.search(body.city(),body.query()); }
    @PostMapping("/ask") Answer ask(@Valid @RequestBody Ask body) { return answers.ask(body.conversationId(),body.city(),body.message()); }
}
