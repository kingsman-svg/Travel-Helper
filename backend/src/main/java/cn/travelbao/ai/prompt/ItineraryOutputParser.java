package cn.travelbao.ai.prompt;

import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.trip.dto.Itinerary;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/** Identical repeated fields are losslessly normalized; conflicting repeated fields are rejected. */
@Component
public class ItineraryOutputParser {
    private final ObjectMapper json;
    public ItineraryOutputParser(ObjectMapper json) { this.json = json; }
    public Itinerary parse(String text) { return parse(text,Itinerary.class); }
    public <T> T parse(String text,Class<T> type) {
        if (text == null || text.isBlank()) throw invalid("模型未返回行程 JSON");
        try (var parser = json.getFactory().createParser(text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid("行程必须是一个 JSON 对象");
            JsonNode root = value(parser);
            if (parser.nextToken() != null) throw invalid("行程 JSON 后不能附加其他内容");
            return new BeanOutputConverter<>(type).convert(root.toString());
        } catch (ServiceProblem e) { throw e; }
        catch (Exception e) { throw invalid("行程 JSON 格式或字段类型不正确"); }
    }
    private JsonNode value(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                ObjectNode node = json.createObjectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid("JSON 字段格式不正确");
                    String key = parser.currentName(); parser.nextToken();
                    JsonNode child = value(parser);
                    if (node.has(key) && !node.get(key).equals(child)) throw invalid("JSON 字段 " + key + " 重复且值冲突");
                    node.set(key,child);
                }
                yield node;
            }
            case START_ARRAY -> {
                ArrayNode node = json.createArrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) node.add(value(parser));
                yield node;
            }
            case VALUE_STRING -> TextNode.valueOf(parser.getText());
            case VALUE_NUMBER_INT -> BigIntegerNode.valueOf(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> DecimalNode.valueOf(parser.getDecimalValue());
            case VALUE_TRUE -> BooleanNode.TRUE;
            case VALUE_FALSE -> BooleanNode.FALSE;
            case VALUE_NULL -> NullNode.instance;
            default -> throw invalid("JSON 内容不完整");
        };
    }
    private static ServiceProblem invalid(String message) { return new ServiceProblem("INVALID_MODEL_OUTPUT",message); }
}
