package com.example.bygynal;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface Users extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.email = :email")
    Optional<UserAccount> lockByEmail(String email);
}
