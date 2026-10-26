package cn.travelbao.ai.prompt;

import cn.travelbao.common.exception.*;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.ItineraryValidator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

/** One initial answer and at most one correction; invalid answers never enter the business draft. */
@Component
public class StructuredPlanGenerator {
    private final ChatClient client;
    private final TravelPrompts prompts;
    private final ItineraryValidator validator;
    private final ItineraryOutputParser parser;
    public StructuredPlanGenerator(ChatClient client, TravelPrompts prompts, ItineraryValidator validator, ItineraryOutputParser parser) {
        this.client = client; this.prompts = prompts; this.validator = validator; this.parser = parser;
    }
    public static OpenAiChatOptions options() {
        return OpenAiChatOptions.builder().responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT,null))
                .temperature(0.3).maxTokens(10000).build();
    }
    public Itinerary generate(String conversationId, String type, TravelRequest request, Itinerary previous, String instruction) {
        String user = prompts.plan(request,previous,instruction), correction = "";
        ServiceProblem last = null;
        for (int attempt=0; attempt<2; attempt++) {
            String reply;
            try {
                reply = client.prompt().system(prompts.system(type)).user(user + correction).options(options())
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID,conversationId)).call().content();
            } catch (Exception e) { throw ProblemMessages.describe(e); }
            try {
                Itinerary itinerary = parser.parse(reply);
                validator.check(request,itinerary,TravelPrompts.VERSION);
                itinerary = AdjustmentRules.apply(previous,itinerary,instruction);
                validator.check(request,itinerary,TravelPrompts.VERSION);
                validator.check(request,itinerary,TravelPrompts.VERSION);
                return itinerary;
            } catch (ServiceProblem e) {
                last = e;
                correction = "\n上一版未通过校验：" + e.getMessage() + "\n请修复此问题并重新输出完整 JSON，保留全部已确认需求和未要求修改的日期。";
            }
        }
        throw new ServiceProblem("INVALID_MODEL_OUTPUT", "模型行程经一次修正仍未通过校验，原行程已保留。" + (last == null ? "" : last.getMessage()));
    }
}
