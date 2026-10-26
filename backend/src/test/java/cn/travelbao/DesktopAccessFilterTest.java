package cn.travelbao;
import cn.travelbao.config.DesktopAccessFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class DesktopAccessFilterTest {
    @Test void desktopApiRejectsMissingAndWrongToken() throws Exception {
        for (String supplied : new String[]{"", "wrong"}) {
            var request = new MockHttpServletRequest("POST", "/api/conversations");
            request.addHeader("X-TravelBao-Desktop", supplied);
            var response = new MockHttpServletResponse();var chain = new MockFilterChain();
            new DesktopAccessFilter("launch-secret").doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403);assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void nativeHeaderAndWebViewCookieAreAccepted() throws Exception {
        for (boolean nativeClient : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("GET", "/api/health");
            if (nativeClient) request.addHeader("X-TravelBao-Desktop", "launch-secret");
            else request.setCookies(new Cookie("travelbao_desktop", "launch-secret"));
            var chain = new MockFilterChain();
            new DesktopAccessFilter("launch-secret").doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
    @Test void webDevelopmentAndStaticAssetsRemainAccessible() throws Exception {
        for (var test : new String[][]{{"", "/api/health"}, {"secret", "/assets/app.js"}}) {
            var chain = new MockFilterChain();
            new DesktopAccessFilter(test[0]).doFilter(new MockHttpServletRequest("GET",test[1]), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
