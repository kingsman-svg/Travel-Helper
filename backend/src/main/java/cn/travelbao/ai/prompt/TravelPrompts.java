package cn.travelbao.ai.prompt;

import cn.travelbao.trip.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class TravelPrompts {
    public static final String VERSION = "v2";
    private final ObjectMapper json;
    public TravelPrompts(ObjectMapper json) { this.json = json; }
    public String system(String type) {
        try { return new ClassPathResource("prompts/" + VERSION + "/" + type + ".txt").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("提示词资源缺失", e); }
    }
    public String plan(TravelRequest request, Itinerary previous, String instruction) {
        String context = "已确认需求（不可自行改变）:\n" + encode(request);
        if (previous != null) context += "\n当前有效行程:\n" + encode(previous) + "\n本次调整要求:\n" + instruction;
        return context + "\n只返回行程 JSON。以下示例只说明结构，不可复用示例日期地点：\n"
                + "{\"title\":\"示例旅行\",\"summary\":\"简明概述\",\"days\":[{\"date\":\"2026-01-01\",\"theme\":\"慢游\",\"activities\":[{\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"title\":\"公园散步\",\"location\":\"示例公园\",\"transport\":\"公交，预留20分钟\",\"walkingMinutes\":20,\"costs\":[{\"category\":\"TRANSPORT\",\"amount\":4.00,\"note\":\"全体车费\"}]}]}],\"extraCosts\":[],\"sources\":[],\"pendingConfirmations\":[\"交通和开放时间待核实\"]}\n"
                + new BeanOutputConverter<>(Itinerary.class).getFormat();
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
