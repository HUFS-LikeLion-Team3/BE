package com.finsight.user.controller;

import com.finsight.global.exception.GlobalExceptionHandler;
import com.finsight.global.security.CurrentUser;
import com.finsight.user.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerTests {
    @Test
    void unexpectedServerFailureReturnsGenericError() throws Exception {
        var currentUser = mock(CurrentUser.class);
        var service = mock(UserService.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(service.findMe(userId)).thenThrow(new IllegalStateException("internal detail"));
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(currentUser, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/users/me"));
    }
}
