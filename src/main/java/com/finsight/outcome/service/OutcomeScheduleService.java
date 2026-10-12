package com.finsight.outcome.service;

import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OutcomeScheduleService {

    private static final int BASELINE_DELAY_SESSIONS = 10;
    private static final int OBSERVATION_DELAY_SESSIONS = 10;
    private static final int MAX_LOOKAHEAD_DAYS = 180;

    private static final Set<Integer> HORIZONS = Set.of(1, 5, 20);

    private static final Set<String> DAILY_MARKET_CODES =
            Set.of("USTY", "TDFX", "TDGD", "TDWT");

    private final MarketDataService marketDataService;

    public record BaselineSchedule(
            LocalDate expectedDate,
            LocalDate deadlineDate
    ) {
    }

    public BaselineSchedule calculateBaselineSchedule(
            MarketTarget target,
            OffsetDateTime submittedAt
    ) {
        validateExchangeTarget(target);

        if (submittedAt == null) {
            throw new IllegalArgumentException(
                    "Submission time is required"
            );
        }

        ZoneId zone = ZoneId.of(target.getTimezone());

        LocalDate startDate = submittedAt
                .atZoneSameInstant(zone)
                .toLocalDate();

        LocalDate expectedDate = null;
        LocalDate deadlineDate = null;

        int sessionsAfterExpected = 0;

        for (int i = 0; i < MAX_LOOKAHEAD_DAYS; i++) {
            LocalDate date = startDate.plusDays(i);

            MarketDataProvider.Session session =
                    marketDataService.regularSessionOn(
                            target,
                            date
                    );

            if (session.isClosed()) {
                continue;
            }

            OffsetDateTime closeAt = resolveCloseTime(
                    session,
                    date,
                    zone
            );

            if (!closeAt.isAfter(submittedAt)) {
                continue;
            }

            if (expectedDate == null) {
                expectedDate = date;
                continue;
            }

            sessionsAfterExpected++;

            if (sessionsAfterExpected == BASELINE_DELAY_SESSIONS) {
                deadlineDate = date;
                break;
            }
        }

        if (expectedDate == null || deadlineDate == null) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.pending,
                    "baseline_calendar_not_resolved"
            );
        }

        return new BaselineSchedule(
                expectedDate,
                deadlineDate
        );
    }

    public Map<Integer, LocalDate> calculateObservationDates(
            MarketTarget target,
            LocalDate baselineSessionDate
    ) {
        validateExchangeTarget(target);

        if (baselineSessionDate == null) {
            throw new IllegalArgumentException(
                    "Baseline session date is required"
            );
        }

        Map<Integer, LocalDate> result = new HashMap<>();
        int tradingSessions = 0;

        for (int i = 1; i <= MAX_LOOKAHEAD_DAYS; i++) {
            LocalDate date = baselineSessionDate.plusDays(i);

            MarketDataProvider.Session session =
                    marketDataService.regularSessionOn(
                            target,
                            date
                    );

            if (session.isClosed()) {
                continue;
            }

            resolveCloseTime(
                    session,
                    date,
                    ZoneId.of(target.getTimezone())
            );

            tradingSessions++;

            if (HORIZONS.contains(tradingSessions)) {
                result.put(tradingSessions, date);
            }

            if (tradingSessions == 20) {
                break;
            }
        }

        if (result.size() != HORIZONS.size()) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.pending,
                    "observation_calendar_not_resolved"
            );
        }

        return Map.copyOf(result);
    }

    public Map<Integer, LocalDate> calculateObservationDeadlines(
            MarketTarget target,
            Map<Integer, LocalDate> expectedDates
    ) {
        validateExchangeTarget(target);

        if (expectedDates == null
                || !expectedDates.keySet().equals(HORIZONS)) {
            throw new IllegalArgumentException(
                    "Expected dates for T1, T5 and T20 are required"
            );
        }

        Map<Integer, LocalDate> deadlines = new HashMap<>();

        for (int horizon : HORIZONS) {
            LocalDate expectedDate = expectedDates.get(horizon);

            if (expectedDate == null) {
                throw new IllegalArgumentException(
                        "Missing expected observation date"
                );
            }

            int sessionsAfterExpected = 0;
            LocalDate deadlineDate = null;

            for (int i = 1; i <= MAX_LOOKAHEAD_DAYS; i++) {
                LocalDate date = expectedDate.plusDays(i);

                MarketDataProvider.Session session =
                        marketDataService.regularSessionOn(
                                target,
                                date
                        );

                if (session.isClosed()) {
                    continue;
                }

                resolveCloseTime(
                        session,
                        date,
                        ZoneId.of(target.getTimezone())
                );

                sessionsAfterExpected++;

                if (sessionsAfterExpected
                        == OBSERVATION_DELAY_SESSIONS) {
                    deadlineDate = date;
                    break;
                }
            }

            if (deadlineDate == null) {
                throw new MarketDataProvider.DataException(
                        MarketDataProvider.Status.pending,
                        "observation_deadline_not_resolved"
                );
            }

            deadlines.put(horizon, deadlineDate);
        }

        return Map.copyOf(deadlines);
    }

    private OffsetDateTime resolveCloseTime(
            MarketDataProvider.Session session,
            LocalDate expectedDate,
            ZoneId expectedZone
    ) {
        if (session == null
                || session.date() == null
                || !expectedDate.equals(session.date())
                || session.timezone() == null
                || !expectedZone.equals(session.timezone())
                || session.open() == null
                || session.close() == null
                || !session.open().isBefore(session.close())) {

            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.unavailable,
                    "invalid_market_session"
            );
        }

        LocalDateTime closeDateTime =
                LocalDateTime.of(
                        session.date(),
                        session.close()
                );

        var offsets = expectedZone.getRules()
                .getValidOffsets(closeDateTime);

        if (offsets.size() != 1) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.unavailable,
                    "ambiguous_market_close_time"
            );
        }

        return OffsetDateTime.of(
                closeDateTime,
                offsets.get(0)
        );
    }

    private void validateExchangeTarget(MarketTarget target) {
        if (target == null
                || target.getCalendarCode() == null
                || target.getTimezone() == null) {
            throw new IllegalArgumentException(
                    "Valid market target is required"
            );
        }

        if (DAILY_MARKET_CODES.contains(
                target.getCalendarCode())) {
            throw new UnsupportedOperationException(
                    "Daily valuation markets require a separate calendar policy"
            );
        }

        try {
            ZoneId.of(target.getTimezone());
        } catch (DateTimeException e) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.unavailable,
                    "invalid_target_timezone"
            );
        }
    }
}