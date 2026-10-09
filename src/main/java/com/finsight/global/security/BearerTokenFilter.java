package com.finsight.global.security;

import com.finsight.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import com.finsight.global.exception.ErrorResponse;
import tools.jackson.databind.json.JsonMapper;

public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    public BearerTokenFilter(TokenService tokens) { this.tokens = tokens; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/v1/auth/kakao".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                unauthorized(request, response);
                return;
            }
            var userId = tokens.authenticate(header.substring(7));
            if (userId.isEmpty()) {
                unauthorized(request, response);
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(userId.get(), null,
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(new ErrorResponse(
                OffsetDateTime.now(ZoneId.of("Asia/Seoul")), 401, "Unauthorized",
                "로그인이 필요하거나 토큰이 만료되었습니다.", request.getRequestURI())));
    }
}
