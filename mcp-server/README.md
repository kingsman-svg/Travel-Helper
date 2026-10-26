# 旅游 MCP 服务

基础包名 `cn.travelbao.mcp`。独立 Spring AI MCP Server，Streamable HTTP，默认 `127.0.0.1:8081/mcp`。与主后端之间仅通过 MCP 协议通信，不共享业务实现。

- `tools/ServiceInfoTools.java`：`travel_service_info` 服务信息，版本0.4.0。
- `tools/TourismTools.java`：`weather_forecast`、`search_places`、`plan_route`，含输入定义与校验。
- `provider/PublicTravelProvider.java`：真实公开服务调用、解析、缓存、节流和明确失败状态。
- `provider/ProviderSettings.java`：提供方 URL，默认 Open-Meteo、Photon 与 FOSSGIS Valhalla。

覆盖杭州、苏州、上海，无需额外地图密钥。天气支持上海时区今天至未来15天；路线只接受近期搜索得到的地点 ID，并只提供步行/驾车道路估计。所有结果包含提供方、来源、查询时间和状态；缓存另保留数据获取时间。未知地点、日期超范围或上游失败不会返回虚构数据。`/health` 用于就绪检查。

公开请求至少相隔1.1秒；使用应用标识和缓存，保留服务署名。本机课堂用途，尚未发布为公网服务。边界与政策见 [旅游数据提供方](../docs/tourism-providers.md)。

6项离线测试通过本机临时 HTTP 服务验证返回格式、单位、城市识别、地点ID/路线、缓存和失败状态。真实端到端验收见 [第四天记录](../docs/day4-report.md)。
