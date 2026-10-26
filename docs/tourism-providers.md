# 第四阶段旅游数据提供方

核对日期：2026-10-24。本阶段是本机课堂演示，使用公开服务，无需额外地图密钥。已实现杭州、苏州、上海；其他城市返回 `UNSUPPORTED_CITY`。高德配置项仍为后续预留，本阶段没有调用高德接口。

| MCP 工具 | 实际服务 | 返回内容与边界 |
| --- | --- | --- |
| `weather_forecast` | Open-Meteo Geocoding + Forecast | 旅行日期的网格预报、温度、最高降水概率、WMO天气码、单位、来源和获取时间；支持上海时区今天至未来15天，超出返回 `OUT_OF_RANGE` |
| `search_places` | Photon / OpenStreetMap | 名称、类别、地址、OSM ID、WGS84经纬度和原始链接；按三城预设包围框限制，不是精确行政区过滤；同名公交站和景点须核对类别 |
| `plan_route` | FOSSGIS Valhalla / OpenStreetMap | 步行或驾车道路模型的公里数、分钟数、渡轮/收费标记与地图跳转；不包含实时路况、公交班次或票价 |

路线只能使用此前搜索得到的地点 ID。MCP 缓存这些坐标30分钟，智能体另限制为本次执行实际查到的 ID，模型不能自行编造经纬度。全链路采用 WGS84，不与高德 GCJ-02 坐标混用。地图跳转由外部网站重新计算，结果可能与保存的历史值不同。

地点搜索宜先用短名称，例如“中国茶叶博物馆”；过长的完整馆区名称可能无匹配，可换简称或别名。搜索结果可能同时包含其他相关博物馆，模型必须根据实际名称、类别与知识库地址选择。

## 使用约束与失败处理

- 公开请求串行发起，起始时间至少相隔1.1秒，发送应用 User-Agent 与 X-Client-Id。地点/城市缓存一天、天气15分钟、路线一小时；缓存结果同时保留本次查询时间与原始获取时间。
- Open-Meteo 免费入口面向非商业用途，当前官方条款列出每天10,000次、每小时5,000次、每分钟600次限制；使用 CC BY 4.0 数据署名。课堂展示保留提供方链接。
- Photon 公共实例按公平使用提供服务，可能限流或封禁过量请求，没有可用性保证。
- FOSSGIS 演示路由服务要求低频调用和识别应用；公开发布应用前需重新核对政策并与维护方沟通。本项目没有发布为公网服务。
- 网络、限流或上游格式异常返回 `UNAVAILABLE`；未知地点、无路线和超范围日期分别返回明确状态。前端保留真实失败记录，模型可在限制内重试或生成带待确认项的方案，不以固定数值冒充查询结果。

天气是预报，知识库是注明核对日的静态资料，费用与活动步行分钟为估计。地点检索和道路路线均不能证明场馆当日开放、无障碍可达、出租车价格或票务库存。渡轮线路会明确提示另查运营情况。

## 官方依据

- [Open-Meteo Forecast](https://open-meteo.com/en/docs)、[Geocoding](https://open-meteo.com/en/docs/geocoding-api)、[使用条款](https://open-meteo.com/en/terms)
- [Photon API v1](https://github.com/komoot/photon/blob/master/docs/api-v1.md)、[公共实例](https://photon.komoot.io/)
- [Valhalla 路线字段](https://valhalla.github.io/valhalla/api/route/api-reference/)、[演示服务说明](https://valhalla.github.io/valhalla/#demo-server)、[FOSSGIS 服务政策](https://routing.openstreetmap.de/about.html)
- [GeoJSON 坐标标准 RFC 7946 §4](https://datatracker.ietf.org/doc/html/rfc7946#section-4)、[高德坐标转换说明](https://lbs.amap.com/api/webservice/guide/api/convert)

接口和政策可能变化，后续部署前应再次核对。本机实际协议与结果验收见 `docs/day4-report.md`。
