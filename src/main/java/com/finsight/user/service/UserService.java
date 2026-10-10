package com.finsight.user.service;

import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.user.dto.UserMeResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository users;

    @Transactional(readOnly = true)
    public UserMeResponse findMe(UUID userId) {
        return UserMeResponse.from(users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")));
    }
}
