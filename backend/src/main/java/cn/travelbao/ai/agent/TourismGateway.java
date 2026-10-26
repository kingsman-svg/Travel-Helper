package cn.travelbao.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public interface TourismGateway {
    record Definition(String name,String description,String inputSchema) {}
    List<Definition> definitions();
    JsonNode call(String name,Map<String,Object> arguments);
}
