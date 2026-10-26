package cn.travelbao.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Optional per-launch authentication for the bundled desktop's loopback server. */
@Component
public class DesktopAccessFilter extends OncePerRequestFilter {
    private final String token;
    public DesktopAccessFilter(@Value("${TRAVEL_DESKTOP_TOKEN:}") String token) { this.token = token; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!token.isBlank() && request.getRequestURI().startsWith("/api/")) {
            String supplied = request.getHeader("X-TravelBao-Desktop");
            if (supplied == null && request.getCookies() != null)
                for (var cookie : request.getCookies()) if (cookie.getName().equals("travelbao_desktop")) supplied = cookie.getValue();
            if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"DESKTOP_ACCESS_REQUIRED\",\"message\":\"请从旅游宝桌面窗口访问。\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
