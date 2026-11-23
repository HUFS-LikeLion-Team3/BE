package com.finsight.user.dto;

import java.util.List;

public record UserInterestsRequest(List<Interest> interests) {
    public record Interest(String interestType, String interestKey) {}
}
