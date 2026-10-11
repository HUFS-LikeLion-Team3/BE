package com.finsight.user.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.user.service.UserConsentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/consents")
public class UserConsentController {
    private final CurrentUser currentUser;
    private final UserConsentService consentService;

    @GetMapping
    public java.util.List<UserConsentService.ConsentResponse> findAll() {
        return consentService.findAll(currentUser.id());
    }

    @PostMapping
    public UserConsentService.ConsentResponse consent(@Valid @RequestBody ConsentRequest request) {
        return consentService.consent(currentUser.id(), request.policyType(), request.policyVersion());
    }

    public record ConsentRequest(
            @NotBlank(message = "잘못된 요청입니다.") @Size(max = 30, message = "잘못된 요청입니다.") String policyType,
            @NotBlank(message = "잘못된 요청입니다.") @Size(max = 100, message = "잘못된 요청입니다.") String policyVersion) {}
}
