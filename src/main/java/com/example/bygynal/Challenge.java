package com.example.bygynal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_challenges")
public class Challenge {

    @Id
    public UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 254)
    public String email;

    @Column(nullable = false, length = 30)
    public String purpose;

    @Column(nullable = false, length = 100)
    public String codeHash;

    @Column(nullable = false)
    public Instant expiresAt;

    @Column(nullable = false)
    public Instant sentAt;

    public int attempts;

    public int sessionVersion;

    public boolean consumed;

    @Column(length = 100)
    public String name;

    @Column(length = 100)
    public String passwordHash;

    @Column(length = 25)
    public String phone;
}
