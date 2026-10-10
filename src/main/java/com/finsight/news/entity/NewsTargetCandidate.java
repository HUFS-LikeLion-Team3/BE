package com.finsight.news.entity;
import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.util.UUID;
@Entity
@Table(name="news_target_candidates",uniqueConstraints=@UniqueConstraint(name="uk_news_target_order",columnNames={"news_id","sort_order"}))
@IdClass(NewsTargetCandidate.Key.class) @Getter @NoArgsConstructor
public class NewsTargetCandidate {
 @Id @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="news_id",nullable=false) private News news;
 @Id @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="market_target_id",nullable=false) private MarketTarget target;
 @Column(columnDefinition="TEXT") private String rationale;
 @Column(nullable=false) private int sortOrder;
 public NewsTargetCandidate(News news,MarketTarget target,int sortOrder){this.news=news;this.target=target;this.sortOrder=sortOrder;}
 @EqualsAndHashCode @NoArgsConstructor
 public static class Key implements Serializable {private UUID news; private UUID target;}
}
