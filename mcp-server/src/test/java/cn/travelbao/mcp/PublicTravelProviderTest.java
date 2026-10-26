package cn.travelbao.mcp;

import cn.travelbao.mcp.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class PublicTravelProviderTest {
    HttpServer server;PublicTravelProvider provider;AtomicInteger requests=new AtomicInteger();
    String geo="{\"results\":[{\"id\":1796236,\"latitude\":31.22222,\"longitude\":121.45806}]}";
    String route="{\"trip\":{\"status\":0,\"summary\":{\"length\":8.313,\"time\":6444.265,\"has_ferry\":true}}}";
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.incrementAndGet();String path=exchange.getRequestURI().getPath();String date=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            String body=path.equals("/geo")?geo:path.equals("/weather")?"{\"daily\":{\"time\":[\""+date+"\"],\"temperature_2m_max\":[25],\"temperature_2m_min\":[18],\"precipitation_probability_max\":[60],\"weather_code\":[3]},\"daily_units\":{\"temperature_2m_max\":\"°C\"}}":path.equals("/places")?"{\"features\":[{\"geometry\":{\"coordinates\":[121.47,31.23]},\"properties\":{\"osm_id\":1,\"osm_type\":\"W\",\"name\":\"测试馆A\",\"osm_key\":\"tourism\",\"osm_value\":\"museum\"}},{\"geometry\":{\"coordinates\":[121.53,31.22]},\"properties\":{\"osm_id\":2,\"osm_type\":\"W\",\"name\":\"测试馆B\"}}]}":route;
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();provider=new PublicTravelProvider(new ProviderSettings(base+"/geo",base+"/weather",base+"/places",base+"/route"),new ObjectMapper());
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void outOfRangeDateNeverCallsProvider(){var result=provider.weather("上海",LocalDate.now().plusDays(30).toString());assertThat(result.status()).isEqualTo("OUT_OF_RANGE");assertThat(requests).hasValue(0);}
    @Test void weatherMatchesVerifiedCityIdentityAndReturnsDatedUnits(){var result=provider.weather("上海",LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(result.data()).containsKeys("fetchedAt","units","coordinateSystem");assertThat(result.data().get("precipitation_probability_max")).isEqualTo(60);}
    @Test void wrongSuzhouOrShanghaiIdentityIsNeverSilentlyUsed(){geo="{\"results\":[{\"id\":1,\"name\":\"同名村庄\"}]}";assertThat(provider.weather("上海",LocalDate.now().toString()).status()).isEqualTo("NOT_FOUND");assertThat(requests).hasValue(1);}
    @Test void routeRequiresCurrentCityObservedIdsAndPreservesFerryFlag(){assertThat(provider.route("上海","invented-a","invented-b","walking").status()).isEqualTo("INVALID_PLACE");assertThat(requests).hasValue(0);provider.search("上海","博物馆");var result=provider.route("上海","osm:W:1","osm:W:2","walking");assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(result.data()).containsEntry("hasFerry",true).containsEntry("distanceKm",8.313);assertThat(result.message()).contains("渡轮");assertThat(provider.route("杭州","osm:W:1","osm:W:2","walking").status()).isEqualTo("INVALID_PLACE");}
    @Test void repeatedSearchUsesCacheAndOriginalDataTimestamp(){var a=provider.search("上海","博物馆");var b=provider.search("上海","博物馆");assertThat(a.status()).isEqualTo("AVAILABLE");assertThat(b.data().get("fetchedAt")).isEqualTo(a.data().get("fetchedAt"));assertThat(requests).hasValue(1);}
    @Test void unsupportedCityAndModeReturnExplicitStatus(){assertThat(provider.search("不存在的城市","地点").status()).isEqualTo("UNSUPPORTED_CITY");assertThat(provider.route("上海","a","b","transit").status()).isEqualTo("INVALID_ARGUMENT");}
}
