package cn.travelbao;

import cn.travelbao.ai.prompt.TravelPrompts;
import cn.travelbao.ai.prompt.StructuredPlanGenerator;
import cn.travelbao.ai.prompt.ItineraryOutputParser;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.ItineraryValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

/** Opt-in real API evaluation: run scripts/evaluate-prompts.mjs, never part of the offline build. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:prompt-evaluation;DB_CLOSE_DELAY=-1")
class PromptEvaluation {
    @Autowired ChatClient client;
    @Autowired ObjectMapper json;
    @Autowired TravelPrompts prompts;
    @Autowired ItineraryValidator validator;
    @Autowired ItineraryOutputParser parser;
    record Case(String name, TravelRequest request, int maxDailyWalking) {}
    @Test void compareFiveFixedCases() throws Exception {
        var cases = json.readValue(new ClassPathResource("prompt-cases.json").getInputStream(), Case[].class);
        var results = new ArrayList<Map<String,Object>>();
        String baseline = new ClassPathResource("prompts/v1/planning.txt").getContentAsString(StandardCharsets.UTF_8);
        for (var sample : cases) for (String version : List.of("v1","v2")) {
            long start = System.currentTimeMillis();
            var checks = new LinkedHashMap<String,Boolean>();
            String error = "";
            try {
                String reply = client.prompt().system(version.equals("v1") ? baseline : prompts.system("planning"))
                        .user(prompts.plan(sample.request(),null,"")).options(StructuredPlanGenerator.options())
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID,UUID.randomUUID().toString())).call().content();
                var itinerary = parser.parse(reply);
                var draft = validator.check(sample.request(),itinerary,version);
                checks.put("schemaDatesSchedule",true);
                checks.put("withinBudget",!draft.budget().overBudget());
                checks.put("noUnverifiedSources",itinerary.sources().isEmpty());
                checks.put("walkingPreference",draft.schedule().walkingMinutesByDate().values().stream().allMatch(n -> n <= sample.maxDailyWalking()));
                boolean accommodation = !sample.request().budgetIncludes().contains(ExpenseCategory.ACCOMMODATION) || itinerary.days().size() == 1
                        || draft.budget().byCategory().get(ExpenseCategory.ACCOMMODATION).signum() > 0;
                checks.put("coversFoodTransportAndLodging",accommodation && draft.budget().byCategory().get(ExpenseCategory.FOOD).signum() > 0
                        && draft.budget().byCategory().get(ExpenseCategory.TRANSPORT).signum() > 0);
            } catch (Exception e) { checks.put("schemaDatesSchedule",false); error = e instanceof cn.travelbao.common.exception.ServiceProblem ? e.getMessage() : "输出未通过JSON格式校验"; }
            results.add(Map.of("case",sample.name(),"version",version,"checks",checks,"error",error,"durationMs",System.currentTimeMillis()-start));
            System.out.println("Prompt " + sample.name() + " " + version + ": " + checks);
        }
        Path output = Path.of("..", ".data", "verification", "prompt-comparison.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("checkedAt",java.time.Instant.now().toString(),"results",results,
                "note","同一模型、温度与JSON schema；每个提示词每案例运行一次，评分不代表广泛质量结论。"));
    }
}
