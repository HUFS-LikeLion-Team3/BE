package com.finsight.auth.repository;
import com.finsight.auth.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByAuthProviderAndProviderUserId(String authProvider, String providerUserId);
}
