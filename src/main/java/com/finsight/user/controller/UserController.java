package com.finsight.user.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.user.dto.UserMeResponse;
import com.finsight.user.dto.UserInterestsResponse;
import com.finsight.user.dto.UserInterestsRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PutMapping("/me/interests")
    public UserInterestsRequest replaceInterests(@RequestBody UserInterestsRequest request) {
        return userService.replaceInterests(currentUser.id(), request);
    }

    @GetMapping("/me/interests")
    public UserInterestsResponse interests() {
        return userService.findInterests(currentUser.id());
    }

    @GetMapping("/me")
    public UserMeResponse me() {
        return userService.findMe(currentUser.id());
    }
}
