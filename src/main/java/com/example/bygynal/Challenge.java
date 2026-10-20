package com.example.bygynal;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="auth_challenges")
public class Challenge {
    @Id public UUID id = UUID.randomUUID();
    @Column(nullable=false, length=254) public String email;
    @Column(nullable=false, length=30) public String purpose;
    @Column(nullable=false, length=100) public String codeHash;
    @Column(nullable=false) public Instant expiresAt, sentAt;
    public int attempts, sessionVersion;
    public boolean consumed;
    @Column(length=100) public String name, passwordHash;
    @Column(length=25) public String phone;
}
