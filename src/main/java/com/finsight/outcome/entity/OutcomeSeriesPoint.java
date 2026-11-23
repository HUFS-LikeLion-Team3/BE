package com.finsight.outcome.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "outcome_series_points",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_outcome_series_points_target_date",
                        columnNames = {
                                "session_target_id",
                                "trading_date"
                        }
                )
        }
)
@Getter
@NoArgsConstructor
public class OutcomeSeriesPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(
            name = "close_value",
            nullable = false,
            precision = 20,
            scale = 6
    )
    private BigDecimal closeValue;

    @Column(nullable = false)
    private String unit;

    @Column(name = "data_source_name", nullable = false)
    private String dataSourceName;

    @Column(name = "data_source_url")
    private String dataSourceUrl;

    public static OutcomeSeriesPoint create(
            UUID sessionTargetId,
            LocalDate tradingDate,
            BigDecimal closeValue,
            String unit,
            String dataSourceName,
            String dataSourceUrl
    ) {
        if (sessionTargetId == null
                || tradingDate == null
                || closeValue == null
                || unit == null
                || unit.isBlank()
                || dataSourceName == null
                || dataSourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid outcome series point"
            );
        }

        OutcomeSeriesPoint point = new OutcomeSeriesPoint();

        point.sessionTargetId = sessionTargetId;
        point.tradingDate = tradingDate;
        point.closeValue = closeValue;
        point.unit = unit;
        point.dataSourceName = dataSourceName;
        point.dataSourceUrl = dataSourceUrl;

        return point;
    }
}