package com.finsight.news.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.*;

@Entity
@Table(name = "user_news_interests")
@Getter
@NoArgsConstructor
public class UserNewsInterest {
    @Id private UUID userId;
    @ElementCollection @CollectionTable(name = "user_interest_markets", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "market", nullable = false)
    private Set<String> markets = new HashSet<>();
    @ElementCollection @CollectionTable(name = "user_interest_topics", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "topic", nullable = false)
    private Set<String> topics = new HashSet<>();
    public UserNewsInterest(UUID userId, Set<String> markets, Set<String> topics) {
        this.userId = userId;
        this.markets = new HashSet<>(markets);
        this.topics = new HashSet<>(topics);
    }
}
