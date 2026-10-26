package cn.travelbao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.datasource.url=jdbc:h2:mem:stage-smoke;DB_CLOSE_DELAY=-1")
class HistoryStageSmokeTest {
 @Autowired TestRestTemplate http;
 @Test void packagedBackendStartsAndHealthIsReadable(){var r=http.getForEntity("/api/health",String.class);assertThat(r.getStatusCode().value()).isEqualTo(200);assertThat(r.getBody()).contains("UP");}
}
