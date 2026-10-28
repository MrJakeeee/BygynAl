package com.example.bygynal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface Challenges extends JpaRepository<Challenge, UUID> {

    Optional<Challenge> findFirstByEmailAndPurposeOrderBySentAtDesc(
            String email, String purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Challenge c where c.id = :id")
    Optional<Challenge> lockById(UUID id);

    @Modifying
    @Query("update Challenge c set c.consumed = true"
        + " where c.email = :email and c.purpose = :purpose")
    void invalidate(String email, String purpose);
}
