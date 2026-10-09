package com.finsight.auth.service;

import com.finsight.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTests {
    @Test
    void exchangesCodeAndFetchesProfile() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new KakaoClient(builder.build(), "app", "secret", "http://localhost/callback");
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=valid")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=secret")))
                .andRespond(withSuccess("{\"access_token\":\"kakao-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(header("Authorization", "Bearer kakao-token"))
                .andRespond(withSuccess("{\"id\":123,\"properties\":{\"nickname\":\"사용자\"}}", MediaType.APPLICATION_JSON));
        assertEquals(new KakaoClient.Profile(123L, "사용자"), client.authenticate("valid"));
        server.verify();
    }

    @Test
    void invalidGrantIs400AndClientAuthenticationFailureIs401() {
        for (String error : new String[]{"invalid_grant", "invalid_client"}) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new KakaoClient(builder.build(), "app", "secret", "http://localhost/callback");
            server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"" + error + "\"}"));
            var exception = assertThrows(ApiException.class, () -> client.authenticate("bad"));
            assertEquals("invalid_grant".equals(error) ? HttpStatus.BAD_REQUEST : HttpStatus.UNAUTHORIZED, exception.getStatus());
            server.verify();
        }
    }
}
