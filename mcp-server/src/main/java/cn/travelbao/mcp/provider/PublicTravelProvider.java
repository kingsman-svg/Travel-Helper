package cn.travelbao.mcp.provider;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Read-only public APIs. Model/user input never controls an outbound host or supplies route coordinates. */
@Service
@EnableConfigurationProperties(ProviderSettings.class)
public class PublicTravelProvider {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PublicTravelProvider.class);
    public record Result(String status,String provider,String sourceUrl,String queriedAt,String message,Map<String,Object> data) {}
    private record City(String name,int id,String bbox) {}
    private record Place(String id,String city,double lat,double lon,Map<String,Object> view,Instant expires) {}
    private record Cached(JsonNode value,Instant fetchedAt) {}
    private static final Map<String,City> CITIES=Map.of(
            "杭州",new City("杭州",1808926,"119.7,29.9,120.7,30.7"),
            "苏州",new City("苏州",1886760,"119.9,30.7,121.3,32.1"),
            "上海",new City("上海",1796236,"120.8,30.6,122.0,31.9"));
    private final ProviderSettings settings;
    private final ObjectMapper json;
    private final RestClient http;
    private final Map<String,Cached> cache=new LinkedHashMap<>();
    private final Map<String,Place> places=new java.util.concurrent.ConcurrentHashMap<>();
    private long nextRequestNanos;
    public PublicTravelProvider(ProviderSettings settings,ObjectMapper json) {
        this.settings=settings;this.json=json;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(4));factory.setReadTimeout(Duration.ofSeconds(10));
        http=RestClient.builder().requestFactory(factory).defaultHeader("User-Agent","TravelBao-Classroom/0.4 (local educational demo)")
                .defaultHeader("X-Client-Id","travelbao-local-classroom").build();
    }
    public Result weather(String city,String date) {
        String source="https://open-meteo.com/",provider="Open-Meteo";
        try {
            City area=city(city);LocalDate requested;
            try {requested=LocalDate.parse(date);}catch(Exception e){return result("INVALID_ARGUMENT",provider,source,"日期须为 YYYY-MM-DD。",Map.of());}
            LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if(requested.isBefore(today)||requested.isAfter(today.plusDays(15))) return result("OUT_OF_RANGE",provider,source,"预报仅支持今天至未来第15天，不能确认所选旅行日期的天气。",Map.of("from",today.toString(),"to",today.plusDays(15).toString()));
            var geo=fetch(url(settings.geocodingUrl(),Map.of("name",area.name(),"count",10,"language","zh","countryCode","CN")),Duration.ofDays(1));
            JsonNode location=null;
            for(var candidate:geo.value().path("results"))if(candidate.path("id").asInt()==area.id())location=candidate;
            if(location==null) return result("NOT_FOUND",provider,source,"无法核对该城市的行政区和天气坐标。",Map.of());
            var values=fetch(url(settings.weatherUrl(),Map.of("latitude",location.path("latitude").asDouble(),"longitude",location.path("longitude").asDouble(),
                    "daily","temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code","timezone","Asia/Shanghai","start_date",date,"end_date",date)),Duration.ofMinutes(15));
            var daily=values.value().path("daily");
            if(!date.equals(daily.path("time").path(0).asText()))throw new IllegalStateException();
            var data=new LinkedHashMap<String,Object>();data.put("city",area.name());data.put("date",date);data.put("timezone","Asia/Shanghai");
            for(String key:List.of("temperature_2m_max","temperature_2m_min","precipitation_probability_max","weather_code")) {
                var value=daily.path(key).path(0);if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new IllegalStateException();data.put(key,value.numberValue());
            }
            data.put("units",json.convertValue(values.value().path("daily_units"),Map.class));data.put("fetchedAt",values.fetchedAt().toString());
            data.put("coordinateSystem","WGS84");data.put("attribution","Weather data by Open-Meteo.com · CC BY 4.0");
            return result("AVAILABLE",provider,source,"城市网格天气预报，不是现场实况；预报可能变化。",data);
        }catch(UnsupportedOperationException e){return unsupported(provider,source);}
        catch(Exception e){diagnose(provider,e);return failure(provider,source);}
    }
    public Result search(String city,String keyword) {
        String source="https://photon.komoot.io/",provider="Photon / OpenStreetMap";
        try {
            City area=city(city);keyword=text(keyword,80);
            var response=fetch(url(settings.placesUrl(),Map.of("q",keyword,"limit",8,"countrycode","CN","bbox",area.bbox())),Duration.ofDays(1));
            var items=new ArrayList<Map<String,Object>>();
            for(var feature:response.value().path("features")) {
                var p=feature.path("properties");var coordinates=feature.path("geometry").path("coordinates");
                if(coordinates.size()<2||!coordinates.get(0).isNumber()||!coordinates.get(1).isNumber())continue;
                double lon=coordinates.get(0).asDouble(),lat=coordinates.get(1).asDouble();
                if(!inside(area,lon,lat)||p.path("osm_id").asLong(0)<=0||p.path("name").asText().isBlank())continue;
                String type=p.path("osm_type").asText();String osmType=switch(type){case "N"->"node";case "W"->"way";case "R"->"relation";default->"";};
                if(osmType.isBlank())continue;
                String id="osm:"+type+":"+p.path("osm_id").asText();
                var view=new LinkedHashMap<String,Object>();view.put("id",id);view.put("name",p.path("name").asText());
                view.put("category",p.path("osm_key").asText()+":"+p.path("osm_value").asText());
                view.put("address",address(p));view.put("latitude",lat);view.put("longitude",lon);view.put("coordinateSystem","WGS84");
                view.put("sourceUrl","https://www.openstreetmap.org/"+osmType+"/"+p.path("osm_id").asText());
                places.put(id,new Place(id,area.name(),lat,lon,Map.copyOf(view),Instant.now().plus(Duration.ofMinutes(30))));items.add(view);
            }
            places.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
            if(places.size()>500)places.clear(); // Bounded demo cache; expired IDs require another search.
            return result(items.isEmpty()?"NOT_FOUND":"AVAILABLE",provider,source,items.isEmpty()?"指定城市范围内未找到地点，请用更短的关键词或别名重试。":"地点来自 OSM，注意同名公交站/地铁站与实际景点的类别差异。",Map.of("city",area.name(),"query",keyword,"places",items.stream().limit(5).toList(),"fetchedAt",response.fetchedAt().toString(),"attribution","© OpenStreetMap contributors · ODbL"));
        }catch(UnsupportedOperationException e){return unsupported(provider,source);}
        catch(IllegalArgumentException e){return result("INVALID_ARGUMENT",provider,source,"城市或关键词不能为空，关键词不超过80字。",Map.of());}
        catch(Exception e){diagnose(provider,e);return failure(provider,source);}
    }
    public Result route(String city,String originPlaceId,String destinationPlaceId,String mode) {
        String source="https://valhalla.openstreetmap.de/",provider="FOSSGIS Valhalla / OpenStreetMap";
        try {
            City area=city(city);
            if(!Set.of("driving","walking").contains(mode))return result("INVALID_ARGUMENT",provider,source,"路线只支持 driving 或 walking；没有实时公交能力。",Map.of());
            Place from=places.get(originPlaceId),to=places.get(destinationPlaceId);
            if(from==null||to==null||from.expires().isBefore(Instant.now())||to.expires().isBefore(Instant.now())||!from.city().equals(area.name())||!to.city().equals(area.name()))
                return result("INVALID_PLACE",provider,source,"请先在本城市搜索起终点，并使用返回的地点 ID（有效30分钟）。",Map.of());
            if(from.id().equals(to.id()))return result("INVALID_ARGUMENT",provider,source,"请选择两个不同地点。",Map.of());
            var payload=Map.of("locations",List.of(Map.of("lat",from.lat(),"lon",from.lon()),Map.of("lat",to.lat(),"lon",to.lon())),
                    "costing",mode.equals("walking")?"pedestrian":"auto","directions_options",Map.of("units","kilometers"));
            var response=fetch(url(settings.routeUrl(),Map.of("json",json.writeValueAsString(payload))),Duration.ofHours(1));
            var trip=response.value().path("trip");var summary=trip.path("summary");
            if(trip.path("status").asInt(-1)!=0||!summary.path("length").isNumber()||!summary.path("time").isNumber())return result("NO_ROUTE",provider,source,"提供方没有返回可用路线。",Map.of());
            double km=summary.path("length").asDouble(),seconds=summary.path("time").asDouble();
            if(!Double.isFinite(km)||!Double.isFinite(seconds)||km<=0||seconds<=0)throw new IllegalStateException();
            var data=new LinkedHashMap<String,Object>();data.put("city",area.name());data.put("origin",from.view());data.put("destination",to.view());data.put("mode",mode);
            data.put("distanceKm",km);data.put("durationMinutes",Math.round(seconds/60.0*10)/10.0);data.put("hasFerry",summary.path("has_ferry").asBoolean());
            data.put("hasToll",summary.path("has_toll").asBoolean());data.put("fetchedAt",response.fetchedAt().toString());data.put("coordinateSystem","WGS84");
            data.put("mapUrl","https://www.openstreetmap.org/directions?engine="+(mode.equals("walking")?"fossgis_osrm_foot":"fossgis_osrm_car")+"&route="+from.lat()+","+from.lon()+";"+to.lat()+","+to.lon());
            data.put("attribution","Routing by FOSSGIS Valhalla · © OpenStreetMap contributors");
            return result("AVAILABLE",provider,source,"基于道路数据的预计路线，无实时路况/班次；包含渡轮时须另行核对运营，地图链接会重新计算。",data);
        }catch(UnsupportedOperationException e){return unsupported(provider,source);}
        catch(Exception e){diagnose(provider,e);return failure(provider,source);}
    }
    private synchronized Cached fetch(String url,Duration ttl) throws InterruptedException {
        var cached=cache.get(url);if(cached!=null&&cached.fetchedAt().plus(ttl).isAfter(Instant.now()))return cached;
        long wait=nextRequestNanos-System.nanoTime();if(wait>0)Thread.sleep(Math.max(1,wait/1_000_000));
        nextRequestNanos=System.nanoTime()+1_100_000_000L;
        JsonNode body=http.get().uri(URI.create(url)).retrieve().body(JsonNode.class);if(body==null)throw new IllegalStateException();
        Cached item=new Cached(body,Instant.now());cache.put(url,item);if(cache.size()>200)cache.remove(cache.keySet().iterator().next());return item;
    }
    private static String url(String base,Map<String,?> parameters){var b=UriComponentsBuilder.fromUriString(base);parameters.forEach(b::queryParam);return b.build().encode().toUriString();}
    private static City city(String value){value=text(value,60);if(value.endsWith("市"))value=value.substring(0,value.length()-1);var city=CITIES.get(value);if(city==null)throw new UnsupportedOperationException();return city;}
    private static String text(String s,int max){if(s==null||s.isBlank()||s.strip().length()>max)throw new IllegalArgumentException();return s.strip();}
    private static boolean inside(City area,double lon,double lat){var b=Arrays.stream(area.bbox().split(",")).mapToDouble(Double::parseDouble).toArray();return Double.isFinite(lon)&&Double.isFinite(lat)&&lon>=b[0]&&lon<=b[2]&&lat>=b[1]&&lat<=b[3];}
    private static String address(JsonNode p){var fields=new ArrayList<String>();for(String k:List.of("state","city","district","street","housenumber")){String v=p.path(k).asText();if(!v.isBlank()&&!fields.contains(v))fields.add(v);}return String.join(" · ",fields);}
    private static Result result(String status,String provider,String source,String message,Map<String,Object> data){return new Result(status,provider,source,Instant.now().toString(),message,data);}
    private static Result failure(String p,String s){return result("UNAVAILABLE",p,s,"提供方暂不可用或返回格式异常，请稍后重试；本次没有有效查询结果。",Map.of());}
    private static void diagnose(String provider,Exception error){log.warn("{} request failed: {} at {}",provider,error.getClass().getSimpleName(),Arrays.stream(error.getStackTrace()).filter(e->e.getClassName().equals(PublicTravelProvider.class.getName())).findFirst().map(Object::toString).orElse("provider"));}
    private static Result unsupported(String p,String s){return result("UNSUPPORTED_CITY",p,s,"当前真实工具覆盖杭州、苏州、上海，其他城市暂未接入。",Map.of());}
}
