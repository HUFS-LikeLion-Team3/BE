package com.finsight.news.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="market_targets") @Getter @NoArgsConstructor
@org.hibernate.annotations.Check(constraints = "market_category in ('korea_equity','us_equity','sector','fx','rate','commodity') and target_type in ('stock','index','etf','exchange_rate','yield','commodity')")
public class MarketTarget {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false,unique=true) private String code;
 @Column(nullable=false) private String displayName;
 @Column(nullable=false) private String marketCategory;
 @Column(nullable=false) private String targetType;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false) private Map<String,String> directionLabels;
 @Column(nullable=false) private String unit;
 @Column(nullable=false) private String calendarCode;
 @Column(nullable=false) private String timezone;
 @Column(nullable=false) private String dataSymbol;
 @org.hibernate.annotations.ColumnDefault("true")
 @Column(name="is_active",nullable=false) private boolean active=true;
 @Column(nullable=false,updatable=false) private Instant createdAt;
 @Column(nullable=false) private Instant updatedAt;
 @PrePersist void create(){createdAt=Instant.now();updatedAt=createdAt;}
 @PreUpdate void update(){updatedAt=Instant.now();}
}
