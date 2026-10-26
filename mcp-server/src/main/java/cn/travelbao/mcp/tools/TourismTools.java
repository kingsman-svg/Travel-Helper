package cn.travelbao.mcp.tools;

import cn.travelbao.mcp.provider.PublicTravelProvider;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class TourismTools {
    private final PublicTravelProvider provider;
    public TourismTools(PublicTravelProvider provider){this.provider=provider;}
    @Tool(name="weather_forecast",description="查询杭州/苏州/上海指定日期的 Open-Meteo 天气预报，限今天至未来15天；越界或失败返回明确状态，不是现场实况。")
    public PublicTravelProvider.Result weather(@ToolParam(description="城市：杭州、苏州或上海") String city,@ToolParam(description="YYYY-MM-DD 日期") String date){return provider.weather(city,date);}
    @Tool(name="search_places",description="通过 Photon/OSM 搜索指定城市的地点，返回真实地点ID、名称、类别、WGS84坐标和来源。注意同名公交站。")
    public PublicTravelProvider.Result search(@ToolParam(description="城市：杭州、苏州或上海") String city,@ToolParam(description="简短场馆名或别名，例如中国茶叶博物馆；城市已限定范围，无结果时缩短关键词，最多80字") String keyword){return provider.search(city,keyword);}
    @Tool(name="plan_route",description="通过 Valhalla 查询同城两个已查到地点之间的道路路线。只能使用 search_places 返回的地点ID，有效30分钟；支持驾车和步行，非实时路况，无公交能力。")
    public PublicTravelProvider.Result route(@ToolParam(description="城市") String city,@ToolParam(description="搜索返回的起点ID") String originPlaceId,
            @ToolParam(description="搜索返回的终点ID") String destinationPlaceId,@ToolParam(description="driving 或 walking") String mode){return provider.route(city,originPlaceId,destinationPlaceId,mode);}
}
