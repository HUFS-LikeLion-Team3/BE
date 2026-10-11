package com.finsight.user.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.user.service.UserOnboardingService;
import com.finsight.user.service.UserOnboardingService.OnboardingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/onboarding")
public class UserOnboardingController {
    private final CurrentUser currentUser;
    private final UserOnboardingService service;

    @RequestMapping(method = {RequestMethod.PATCH, RequestMethod.POST})
    public OnboardingResponse complete() {
        return service.complete(currentUser.id());
    }
}
