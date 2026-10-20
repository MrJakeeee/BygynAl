package com.example.bygynal;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface Challenges extends JpaRepository<Challenge, UUID> {
    Optional<Challenge> findFirstByEmailAndPurposeOrderBySentAtDesc(String email, String purpose);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Challenge c where c.id = :id")
    Optional<Challenge> lockById(UUID id);
    @Modifying @Query("update Challenge c set c.consumed = true where c.email = :email and c.purpose = :purpose")
    void invalidate(String email, String purpose);
}
