package com.finsight.news.repository;

import com.finsight.news.entity.UserNewsInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserNewsInterestRepository extends JpaRepository<UserNewsInterest, UUID> {}
