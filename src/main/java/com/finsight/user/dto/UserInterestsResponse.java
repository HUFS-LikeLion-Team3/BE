package com.finsight.user.dto;

import com.finsight.news.entity.UserInterest;
import java.util.List;
import java.util.UUID;

public record UserInterestsResponse(List<Interest> interests) {
    public record Interest(UUID id, UserInterest.InterestType interestType, String interestKey) {
        public static Interest from(UserInterest interest) {
            String key = interest.getInterestKey();
            if (interest.getInterestType() == UserInterest.InterestType.market) {
                key = switch (key) {
                    case "korea_equity" -> "korea";
                    case "us_equity" -> "us";
                    default -> key;
                };
            }
            return new Interest(interest.getId(), interest.getInterestType(), key);
        }
    }
}
