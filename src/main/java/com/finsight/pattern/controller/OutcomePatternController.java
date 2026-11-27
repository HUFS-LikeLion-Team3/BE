package com.finsight.pattern.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.pattern.dto.OutcomePatternResponse;
import com.finsight.pattern.service.OutcomePatternService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/patterns")
public class OutcomePatternController {
    private final CurrentUser currentUser;
    private final OutcomePatternService outcomePatternService;

    public OutcomePatternController(CurrentUser currentUser, OutcomePatternService outcomePatternService) {
        this.currentUser = currentUser;
        this.outcomePatternService = outcomePatternService;
    }

    @GetMapping("/outcomes")
    public OutcomePatternResponse outcomes(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) Integer horizon) {
        return outcomePatternService.outcomes(currentUser.id(), market, horizon);
    }
}
