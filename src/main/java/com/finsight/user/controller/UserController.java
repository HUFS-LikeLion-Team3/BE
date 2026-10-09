package com.finsight.user.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.user.dto.UserMeResponse;
import com.finsight.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final CurrentUser currentUser;
    private final UserService userService;

    @GetMapping("/me")
    public UserMeResponse me() {
        return userService.findMe(currentUser.id());
    }
}
