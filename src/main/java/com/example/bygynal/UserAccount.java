package com.example.bygynal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class UserAccount {

    @Id
    public UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 254)
    public String email;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(nullable = false, length = 25)
    public String phone;

    @Column(nullable = false, length = 100)
    public String passwordHash;

    @Column(nullable = false, length = 20)
    public String role = "USER";

    public UUID establishmentId;

    public boolean blocked;

    public boolean twoFactor;

    public boolean mustChangePassword;

    public int failedLogins;

    public int sessionVersion;

    public Instant lockedUntil;
}
