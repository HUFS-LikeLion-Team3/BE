package com.finsight.user.service;

import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.user.dto.UserMeResponse;
import com.finsight.user.dto.UserInterestsResponse;
import com.finsight.news.repository.UserInterestRepository;
import com.finsight.news.entity.UserInterest;
import com.finsight.user.dto.UserInterestsRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository users;
    private final UserInterestRepository interests;

    // Registered service topics; the ERD stores keys directly without a topic table.
    private static final Set<String> TOPIC_KEYS = Set.of("semiconductor", "rate");

    @Transactional
    public UserInterestsRequest replaceInterests(UUID userId, UserInterestsRequest request) {
        if (request == null || request.interests() == null) throw invalidInterests();
        var seen = new HashSet<UserInterestsRequest.Interest>();
        for (var item : request.interests()) {
            if (item == null || item.interestType() == null || item.interestKey() == null
                    || !seen.add(item)) throw invalidInterests();
            boolean valid = switch (item.interestType()) {
                case "market" -> Set.of("korea", "us").contains(item.interestKey());
                case "topic" -> TOPIC_KEYS.contains(item.interestKey());
                default -> false;
            };
            if (!valid) throw invalidInterests();
        }
        var user = users.findByIdForUpdate(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        interests.deleteByUserId(userId);
        // Delete before inserting so repeated requests respect the ERD unique constraint.
        interests.flush();
        interests.saveAll(request.interests().stream().map(item -> new UserInterest(user,
                UserInterest.InterestType.valueOf(item.interestType()), item.interestKey())).toList());
        interests.flush();
        return request;
    }

    private ApiException invalidInterests() {
        return new ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 관심사 정보입니다.");
    }

    @Transactional(readOnly = true)
    public UserInterestsResponse findInterests(UUID userId) {
        return new UserInterestsResponse(interests.findByUserId(userId).stream()
                .map(UserInterestsResponse.Interest::from).toList());
    }

    @Transactional(readOnly = true)
    public UserMeResponse findMe(UUID userId) {
        return UserMeResponse.from(users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")));
    }
}
