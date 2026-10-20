package com.example.bygynal;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements CommandLineRunner {
    final AuthService auth;
    final Users users;
    @Value("${app.bootstrap-email:}") String email;
    @Value("${app.bootstrap-password:}") String password;
    public BootstrapAdmin(AuthService auth,Users users) {this.auth=auth;this.users=users;}
    @Override @Transactional public void run(String... args) {
        if(email.isBlank()&&password.isBlank()) return;
        String address=AuthService.email(email);
        if(users.findByEmail(address).isPresent()) return;
        if(password.length()<12||!password.matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).*")) throw new IllegalStateException("ADMIN_PASSWORD: минимум 12 символов, буквы и цифры");
        UserAccount u=new UserAccount(); u.email=address; u.name="Администратор"; u.phone="не указан";
        u.role="ADMIN"; u.mustChangePassword=true; u.passwordHash=auth.hashPassword(password); users.save(u);
    }
}
