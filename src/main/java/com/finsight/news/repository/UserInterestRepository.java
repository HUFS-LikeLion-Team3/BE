package com.finsight.news.repository;
import com.finsight.news.entity.UserInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface UserInterestRepository extends JpaRepository<UserInterest,UUID>{
 List<UserInterest> findByUserId(UUID userId);
 void deleteByUserId(UUID userId);
}
