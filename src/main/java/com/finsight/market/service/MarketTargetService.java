package com.finsight.market.service;

import com.finsight.global.exception.ApiException;
import com.finsight.market.dto.MarketTargetResponses.*;
import com.finsight.market.repository.MarketTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketTargetService {
    private static final Set<String> CATEGORIES = Set.of(
            "korea_equity", "us_equity", "sector", "fx", "rate", "commodity");
    private static final Set<String> TYPES = Set.of(
            "stock", "index", "etf", "exchange_rate", "yield", "commodity");
    private final MarketTargetRepository repository;
    private final ReferenceMarketService referenceMarketService;

    public Detail detail(UUID id) {
        var target = repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "시장 대상을 찾을 수 없습니다."));
        return new Detail(target.getId(), target.getCode(), target.getDisplayName(),
                target.getMarketCategory(), target.getTargetType(), target.getDirectionLabels(),
                target.getUnit(), target.isActive(), referenceMarketService.get(target));
    }

    public ListResponse list(String marketCategory, String targetType) {
        if ((marketCategory != null && !CATEGORIES.contains(marketCategory))
                || (targetType != null && !TYPES.contains(targetType))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 시장 대상 조회 조건입니다.");
        }
        return new ListResponse(repository.findActive(marketCategory, targetType)
                .stream().map(Target::from).toList());
    }
}
