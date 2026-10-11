package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import static com.finsight.market.data.MarketDataProvider.*;

/** Read-only market data queries. Never creates or changes session baselines/observations. */
@Service
public class MarketDataService {
    private final MarketDataProvider provider;
    private final Clock clock;
    private final Duration confirmationDelay;
    private final int lookbackDays;
    private final List<DailyObservationProvider> dailyProviders;

    @Autowired
    public MarketDataService(MarketDataProvider provider,
                             List<DailyObservationProvider> dailyProviders,
                             @Value("${market.data.confirmation-delay:PT15M}") Duration confirmationDelay,
                             @Value("${market.data.max-lookback-days:14}") int lookbackDays) {
        this(provider, dailyProviders, Clock.systemUTC(), confirmationDelay, lookbackDays);
    }

    public MarketDataService(MarketDataProvider provider, Duration confirmationDelay, int lookbackDays) {
        this(provider, List.of(), Clock.systemUTC(), confirmationDelay, lookbackDays);
    }

    MarketDataService(MarketDataProvider provider, Clock clock, Duration confirmationDelay, int lookbackDays) {
        this(provider, List.of(), clock, confirmationDelay, lookbackDays);
    }

    MarketDataService(MarketDataProvider provider, List<DailyObservationProvider> dailyProviders,
                      Clock clock, Duration confirmationDelay, int lookbackDays) {
        if (confirmationDelay.isNegative() || lookbackDays < 1 || lookbackDays > 31)
            throw new IllegalArgumentException("Invalid market data query settings");
        this.provider = provider;
        this.clock = clock;
        this.confirmationDelay = confirmationDelay;
        this.lookbackDays = lookbackDays;
        this.dailyProviders = List.copyOf(dailyProviders);
    }

    public record Close(LocalDate sessionDate, OffsetDateTime asOfAt, BigDecimal value,
                        String unit, String dataSourceName, String dataSourceUrl) {}
    public record ClosePair(Close latest, Close previous) {}
    public record Result<T>(Status status, T data, String diagnostic) {
        public Result(Status status, T data) { this(status, data, null); }
    }

    /** Also used by outcome collectors to obtain the exact requested session, never a nearby value. */
    public Result<Close> regularCloseOn(MarketTarget target, LocalDate date) {
        try {
            validate(target);
            var daily = dailyProvider(target);
            if (daily != null) {
                var requested = daily.observations(target, date, lookbackDays).stream()
                        .filter(value -> value != null && date.equals(value.sessionDate())).toList();
                var observation = normalizeObservations(requested).stream().findFirst()
                        .orElseThrow(() -> new DataException(Status.unavailable, "missing_requested_observation"));
                if (!confirmed(observation)) throw new DataException(Status.pending);
                return new Result<>(Status.ready, observationClose(target, daily, observation));
            }
            var session = regularSessionOn(target, date);
            return new Result<>(Status.ready, close(target, session));
        } catch (DataException e) {
            return new Result<>(e.status(), null, e.getMessage());
        }
    }

    /** Date-specific provider schedule including holidays, early closes and DST. */
    public Session regularSessionOn(MarketTarget target, LocalDate date) {
        validate(target);
        if (dailyProvider(target) != null)
            throw new DataException(Status.unavailable, "daily_valuation_has_no_exchange_session");
        return provider.session(target.getCalendarCode(), ZoneId.of(target.getTimezone()), date);
    }

    public Result<ClosePair> latestRegularCloses(MarketTarget target) {
        try {
            validate(target);
            var daily = dailyProvider(target);
            if (daily != null) return latestDailyObservations(target, daily);
            var today = LocalDate.now(clock.withZone(ZoneId.of(target.getTimezone())));
            var session = regularSessionOn(target, today);
            if (session.isClosed() || !confirmed(session)) session = previousSession(target, today);
            var latest = provider.latestEndOfDay(target.getDataSymbol(), target.getCalendarCode());
            if (latest == null || latest.date() == null || latest.value() == null)
                throw new DataException(Status.unavailable);
            // A newer/provisional bar is never evidence; query the exact completed session instead.
            // A stale provider must not present an older session as today's latest close.
            if (latest.date().isBefore(session.date())) throw new DataException(Status.pending);
            if (latest.date().isAfter(today)) throw new DataException(Status.unavailable);
            Close current = latest.date().equals(session.date()) ? toClose(target, session, latest)
                    : close(target, session);
            Close previous = close(target, previousSession(target, session.date()));
            return new Result<>(Status.ready, new ClosePair(current, previous));
        } catch (DataException e) {
            return new Result<>(e.status(), null, e.getMessage());
        }
    }

    private DailyObservationProvider dailyProvider(MarketTarget target) {
        var matches = dailyProviders.stream().filter(value -> value.supports(target)).toList();
        if (matches.size() > 1) throw new DataException(Status.unavailable, "ambiguous_daily_provider");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private Result<ClosePair> latestDailyObservations(MarketTarget target, DailyObservationProvider daily) {
        var today = LocalDate.now(clock.withZone(ZoneId.of(target.getTimezone())));
        var published = daily.observations(target, today, lookbackDays);
        if (published == null || published.stream().anyMatch(value -> value == null || value.sessionDate() == null))
            throw new DataException(Status.unavailable, "incomplete_observation");
        if (published.stream().anyMatch(value -> value.sessionDate().isBefore(today.minusDays(lookbackDays))))
            throw new DataException(Status.pending, "stale_observation");
        var observations = published.stream()
                .sorted(Comparator.comparing(DailyObservationProvider.Observation::sessionDate).reversed()).toList();
        if (observations.stream().anyMatch(value -> value.sessionDate().isAfter(today)))
            throw new DataException(Status.unavailable, "future_observation_date");
        var completed = observations.stream().filter(this::confirmed).toList();
        var dates = completed.stream().map(DailyObservationProvider.Observation::sessionDate).distinct().limit(2).toList();
        if (dates.size() < 2) throw new DataException(Status.pending, "insufficient_finalized_observations");
        var finalized = normalizeObservations(completed.stream().filter(value -> dates.contains(value.sessionDate())).toList())
                .stream().sorted(Comparator.comparing(DailyObservationProvider.Observation::sessionDate).reversed()).toList();
        // Consecutive published observations: do not manufacture holiday or weekend values.
        return new Result<>(Status.ready, new ClosePair(observationClose(target, daily, finalized.get(0)),
                observationClose(target, daily, finalized.get(1))));
    }

    private List<DailyObservationProvider.Observation> normalizeObservations(
            List<DailyObservationProvider.Observation> observations) {
        if (observations == null) throw new DataException(Status.unavailable, "incomplete_observation");
        var unique = new HashMap<LocalDate, DailyObservationProvider.Observation>();
        for (var observation : observations) {
            if (observation == null || observation.sessionDate() == null || observation.asOfAt() == null || observation.value() == null)
                throw new DataException(Status.unavailable, "incomplete_observation");
            var existing = unique.putIfAbsent(observation.sessionDate(), observation);
            if (existing != null && (existing.value().compareTo(observation.value()) != 0
                    || !existing.asOfAt().isEqual(observation.asOfAt())))
                throw new DataException(Status.unavailable, "conflicting_observation_date");
        }
        return List.copyOf(unique.values());
    }

    private boolean confirmed(DailyObservationProvider.Observation observation) {
        if (observation.sessionDate() == null || observation.asOfAt() == null || observation.value() == null)
            throw new DataException(Status.unavailable, "incomplete_observation");
        return !clock.instant().isBefore(observation.asOfAt().toInstant().plus(confirmationDelay));
    }

    private Close observationClose(MarketTarget target, DailyObservationProvider daily,
                                   DailyObservationProvider.Observation observation) {
        return new Close(observation.sessionDate(), observation.asOfAt(), observation.value(),
                target.getUnit(), daily.sourceName(), daily.sourceUrl());
    }

    private Session previousSession(MarketTarget target, LocalDate before) {
        for (int i = 1; i <= lookbackDays; i++) {
            var day = regularSessionOn(target, before.minusDays(i));
            if (!day.isClosed()) return day;
        }
        throw new DataException(Status.unavailable);
    }

    private boolean confirmed(Session session) {
        return !clock.instant().isBefore(closeTime(session).toInstant().plus(confirmationDelay));
    }

    private OffsetDateTime closeTime(Session session) {
        var localClose = session.date().atTime(session.close());
        if (session.timezone().getRules().getValidOffsets(localClose).size() != 1)
            throw new DataException(Status.unavailable);
        return localClose.atZone(session.timezone()).toOffsetDateTime();
    }

    private Close close(MarketTarget target, Session session) {
        if (session.isClosed()) throw new DataException(Status.unavailable);
        if (!confirmed(session)) throw new DataException(Status.pending);
        var eod = provider.endOfDay(target.getDataSymbol(), target.getCalendarCode(), session.date());
        if (eod == null || !session.date().equals(eod.date()) || eod.value() == null)
            throw new DataException(Status.unavailable);
        return toClose(target, session, eod);
    }

    private Close toClose(MarketTarget target, Session session, EndOfDay eod) {
        return new Close(session.date(), closeTime(session), eod.value(), target.getUnit(),
                provider.sourceName(), provider.sourceUrl());
    }

    private void validate(MarketTarget target) {
        if (target.getDataSymbol() == null || target.getDataSymbol().isBlank()
                || target.getCalendarCode() == null || !target.getCalendarCode().matches("[A-Z0-9]{4}")
                || target.getTimezone() == null || target.getUnit() == null) {
            throw new DataException(Status.unavailable);
        }
        try { ZoneId.of(target.getTimezone()); }
        catch (DateTimeException e) { throw new DataException(Status.unavailable); }
    }
}
