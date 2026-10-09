package com.finsight.auth.repository;
import com.finsight.auth.entity.AccessToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccessTokenRepository extends JpaRepository<AccessToken, String> {
    @EntityGraph(attributePaths = "user")
    Optional<AccessToken> findByTokenHash(String tokenHash);
}
