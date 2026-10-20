package com.example.bygynal;
import jakarta.validation.constraints.*;
import java.util.UUID;
public class Requests {
    public record Register(@NotBlank @Size(max=100) String name,
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Pattern(regexp="\\+?[0-9 ()-]{7,25}",message="Укажите корректный телефон") String phone,
        @NotBlank @Size(min=8,max=72) @Pattern(regexp="(?s)(?=.*\\p{L})(?=.*[0-9]).*",message="Нужны буквы и цифры") String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=72) String password) {}
    public record EmailRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record Verify(@NotNull UUID challengeId, @NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record Reset(@NotNull UUID challengeId, @NotBlank @Pattern(regexp="[0-9]{6}") String code,
        @NotBlank @Size(min=8,max=72) @Pattern(regexp="(?s)(?=.*\\p{L})(?=.*[0-9]).*") String password) {}
    public record Password(@NotBlank @Size(max=72) String currentPassword,
        @NotBlank @Size(min=8,max=72) @Pattern(regexp="(?s)(?=.*\\p{L})(?=.*[0-9]).*") String password) {}
    public record TwoFactor(@NotBlank @Size(max=72) String password, boolean enabled) {}
    public record Profile(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="\\+?[0-9 ()-]{7,25}") String phone) {}
    public record ChangeEmail(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=72) String password) {}
    public record Establishment(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=300) String address) {}
    public record Seller(@NotBlank @Size(max=100) String name,@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Pattern(regexp="\\+?[0-9 ()-]{7,25}") String phone,@NotNull UUID establishmentId) {}
    public record Block(boolean blocked) {}
}
