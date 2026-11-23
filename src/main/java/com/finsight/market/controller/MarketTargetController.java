package com.finsight.market.controller;

import com.finsight.market.dto.MarketTargetResponses.ListResponse;
import com.finsight.market.service.MarketTargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import com.finsight.market.dto.MarketTargetResponses.Detail;

@RestController
@RequestMapping("/api/v1/market-targets")
@RequiredArgsConstructor
public class MarketTargetController {
    private final MarketTargetService service;

    @GetMapping("/{marketTargetId}")
    public Detail detail(@PathVariable UUID marketTargetId) {
        return service.detail(marketTargetId);
    }

    @GetMapping
    public ListResponse list(@RequestParam(required = false) String marketCategory,
                             @RequestParam(required = false) String targetType) {
        return service.list(marketCategory, targetType);
    }
}
