package com.finsight.news.entity;
import com.finsight.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="user_interests", uniqueConstraints=@UniqueConstraint(name="uk_user_interest",columnNames={"user_id","interest_type","interest_key"}))
@Getter @NoArgsConstructor
public class UserInterest {
 public enum InterestType { market, topic }
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id",nullable=false) private User user;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private InterestType interestType;
 @Column(nullable=false) private String interestKey;
 @Column(nullable=false,updatable=false) private Instant createdAt;
 public UserInterest(User user,InterestType type,String key){this.user=user;this.interestType=type;this.interestKey=key;}
 @PrePersist void create(){createdAt=Instant.now();}
}
