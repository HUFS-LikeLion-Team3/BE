package com.finsight.global.security;

import com.finsight.auth.service.KakaoLoginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final KakaoLoginService loginService;

    @PostMapping("/kakao")
    public KakaoLoginService.LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request.authorizationCode());
    }

    public record LoginRequest(
            @NotBlank(message = "인가 코드는 필수입니다.")
            @Size(max = 2048, message = "인가 코드가 너무 깁니다.") String authorizationCode) {}
}
