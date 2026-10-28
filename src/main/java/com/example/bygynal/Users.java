package com.example.bygynal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface Users extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.email = :email")
    Optional<UserAccount> lockByEmail(String email);
}
