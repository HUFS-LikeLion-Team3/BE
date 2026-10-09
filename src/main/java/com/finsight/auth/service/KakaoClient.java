package com.finsight.auth.service;

import com.finsight.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;

@Component
public class KakaoClient {
    private final RestClient client;
    private final String clientId;
    private final String secret;
    private final String redirectUri;

    @org.springframework.beans.factory.annotation.Autowired
    public KakaoClient(@Value("${auth.kakao.client-id:}") String clientId,
            @Value("${auth.kakao.client-secret:}") String secret,
            @Value("${auth.kakao.redirect-uri}") String redirectUri) {
        this(createRestClient(), clientId, secret, redirectUri);
    }

    private static RestClient createRestClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }

    KakaoClient(RestClient client, String clientId, String secret, String redirectUri) {
        this.client = client;
        this.clientId = clientId;
        this.secret = secret;
        this.redirectUri = redirectUri;
    }

    public String appId() { return clientId; }

    public Profile authenticate(String code) {
        if (clientId.isBlank() || secret.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "카카오 로그인 설정이 필요합니다.");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        Map<?, ?> token;
        try {
            token = client.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            Map<?, ?> error = e.getResponseBodyAs(Map.class);
            if (e.getStatusCode().value() == 400 && error != null && "invalid_grant".equals(error.get("error"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 카카오 인가 코드입니다.");
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다.");
            }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
        }
        if (token == null || !(token.get("access_token") instanceof String accessToken) || accessToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다.");
        }
        Map<?, ?> profile;
        try {
            profile = client.get().uri("https://kapi.kakao.com/v2/user/me")
                    .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().is4xxClientError() ? HttpStatus.UNAUTHORIZED : HttpStatus.INTERNAL_SERVER_ERROR,
                    e.getStatusCode().is4xxClientError() ? "카카오 인증에 실패했습니다." : "서버 내부 오류가 발생했습니다.");
        }
        if (profile == null || !(profile.get("id") instanceof Number id) || id.longValue() <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다.");
        }
        String displayName = "사용자";
        if (profile.get("properties") instanceof Map<?, ?> properties
                && properties.get("nickname") instanceof String nickname && !nickname.isBlank()) {
            displayName = nickname.substring(0, Math.min(100, nickname.length()));
        }
        return new Profile(id.longValue(), displayName);
    }

    public record Profile(Long id, String displayName) {}
}
