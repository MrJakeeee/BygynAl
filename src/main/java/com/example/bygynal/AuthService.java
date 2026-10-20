package com.example.bygynal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
@Transactional(noRollbackFor=ApiError.class)
public class AuthService {
    final Users users; final Challenges challenges; final PasswordEncoder encoder; final JavaMailSender mail;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.mail-from}") String from;
    @Value("${app.code-ttl-minutes:10}") int codeMinutes;
    @Value("${app.reset-ttl-minutes:30}") int resetMinutes;
    @Value("${app.code-max-attempts:5}") int maxCodeAttempts;
    @Value("${app.resend-seconds:60}") int resendSeconds;
    @Value("${app.login-max-attempts:5}") int maxLoginAttempts;
    @Value("${app.lock-minutes:15}") int lockMinutes;
    public AuthService(Users users, Challenges challenges, PasswordEncoder encoder, JavaMailSender mail) {
        this.users=users; this.challenges=challenges; this.encoder=encoder; this.mail=mail;
    }
    static String email(String value) {
        String result=value.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9][a-z0-9.]{0,63}@gmail\\.com"))
            throw new ApiError(400,"Используйте адрес @gmail.com");
        // Gmail ignores dots in the local part. One mailbox must have one account.
        return result.substring(0,result.indexOf('@')).replace(".","")+"@gmail.com";
    }
    String hashPassword(String value) {
        if(value.getBytes(StandardCharsets.UTF_8).length>72) throw new ApiError(400,"Пароль должен занимать не более 72 байт UTF-8");
        return encoder.encode(value);
    }
    boolean matches(String value,String hash) {
        return value.getBytes(StandardCharsets.UTF_8).length<=72 && encoder.matches(value,hash);
    }
    void send(String email,String subject,String body) {
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("BugunAL — "+subject); message.setText(body);
        mail.send(message);
    }
    Challenge issue(String email,String purpose,int minutes) {
        var previous=challenges.findFirstByEmailAndPurposeOrderBySentAtDesc(email,purpose);
        if(previous.isPresent() && previous.get().sentAt.plusSeconds(resendSeconds).isAfter(Instant.now()))
            throw new ApiError(429,"Повторная отправка доступна через "+resendSeconds+" секунд");
        challenges.invalidate(email,purpose);
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        Challenge c=new Challenge(); c.email=email; c.purpose=purpose;
        c.sentAt=Instant.now(); c.expiresAt=c.sentAt.plusSeconds(minutes*60L); c.codeHash=encoder.encode(code);
        c=challenges.save(c);
        send(email,switch(purpose) { case "REGISTER" -> "Подтвердите регистрацию"; case "RESET" -> "Восстановление пароля"; case "LOGIN" -> "Код входа"; default -> "Подтверждение изменения профиля"; },
            "Ваш код: "+code+"\nКод действует "+minutes+" минут и используется один раз. Никому его не сообщайте.\nЕсли вы не отправляли запрос, проигнорируйте письмо.");
        return c;
    }
    Challenge verify(UUID id,String code,String purpose) {
        Challenge c=challenges.lockById(id).orElseThrow(()->new ApiError(400,"Код недействителен или истёк"));
        if(!purpose.equals(c.purpose)||c.consumed||c.expiresAt.isBefore(Instant.now())||c.attempts>=maxCodeAttempts)
            throw new ApiError(400,"Код недействителен или истёк. Запросите новый");
        c.attempts++;
        if(!matches(code,c.codeHash)) throw new ApiError(400,"Неверный код подтверждения");
        c.consumed=true;
        return c;
    }
    public Map<String,Object> register(Requests.Register r) {
        String email=email(r.email());
        if(users.findByEmail(email).isPresent()) throw new ApiError(409,"Этот e-mail уже зарегистрирован");
        String hash=hashPassword(r.password());
        Challenge c=issue(email,"REGISTER",codeMinutes);
        c.name=r.name().trim(); c.phone=r.phone().trim(); c.passwordHash=hash;
        return challengeResponse(c);
    }
    public void confirmRegistration(Requests.Verify r) {
        Challenge c=verify(r.challengeId(),r.code(),"REGISTER");
        if(users.findByEmail(c.email).isPresent()) throw new ApiError(409,"Этот e-mail уже зарегистрирован");
        UserAccount u=new UserAccount(); u.email=c.email; u.name=c.name; u.phone=c.phone; u.passwordHash=c.passwordHash;
        users.saveAndFlush(u);
    }
    void allowed(UserAccount u) {
        if(u.blocked) throw new ApiError(403,"Учётная запись заблокирована администратором");
        if(u.lockedUntil!=null && u.lockedUntil.isAfter(Instant.now()))
            throw new ApiError(429,"Слишком много попыток входа. Повторите через 15 минут");
    }
    public LoginResult login(Requests.Login r) {
        UserAccount u=users.lockByEmail(email(r.email())).orElse(null);
        if(u==null) { matches(r.password(),dummyHash); throw new ApiError(401,"Неверный e-mail или пароль"); }
        allowed(u);
        if(u.lockedUntil!=null) {u.failedLogins=0; u.lockedUntil=null;}
        if(!matches(r.password(),u.passwordHash)) {
            if(++u.failedLogins>=maxLoginAttempts) {u.lockedUntil=Instant.now().plusSeconds(lockMinutes*60L); throw new ApiError(429,"Слишком много попыток входа. Повторите через 15 минут");}
            throw new ApiError(401,"Неверный e-mail или пароль");
        }
        u.failedLogins=0;
        if(u.twoFactor) { Challenge c=issue(u.email,"LOGIN",codeMinutes); c.sessionVersion=u.sessionVersion; return new LoginResult(null,challengeResponse(c)); }
        return new LoginResult(u,null);
    }
    private final String dummyHash="$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    public UserAccount confirmLogin(Requests.Verify r) {
        Challenge c=verify(r.challengeId(),r.code(),"LOGIN");
        UserAccount u=users.lockByEmail(c.email).orElseThrow(()->new ApiError(401,"Войдите заново"));
        allowed(u);
        if(u.sessionVersion!=c.sessionVersion) throw new ApiError(401,"Войдите заново");
        return u;
    }
    public Map<String,Object> forgot(Requests.EmailRequest r) {
        String email=email(r.email());
        UserAccount u=users.lockByEmail(email).orElse(null);
        if(u==null || u.blocked) return Map.of("challengeId",UUID.randomUUID(),"message","Если учётная запись существует, письмо отправлено");
        Challenge c=issue(email,"RESET",resetMinutes); c.sessionVersion=u.sessionVersion;
        return Map.of("challengeId",c.id,"message","Если учётная запись существует, письмо отправлено");
    }
    public void reset(Requests.Reset r) {
        String hash=hashPassword(r.password());
        Challenge c=verify(r.challengeId(),r.code(),"RESET");
        UserAccount u=users.lockByEmail(c.email).orElseThrow(()->new ApiError(400,"Запрос недействителен"));
        if(u.blocked || c.sessionVersion!=u.sessionVersion) throw new ApiError(400,"Запрос недействителен");
        u.passwordHash=hash; u.mustChangePassword=false; u.failedLogins=0; u.lockedUntil=null; u.sessionVersion++;
    }
    public UserAccount user(UUID id) { return users.findById(id).orElseThrow(()->new ApiError(401,"Войдите заново")); }
    UserAccount lockedUser(UUID id) { return users.lockByEmail(user(id).email).orElseThrow(); }
    public void changePassword(UUID id,Requests.Password r) {
        UserAccount u=lockedUser(id);
        if(!matches(r.currentPassword(),u.passwordHash)) throw new ApiError(400,"Неверный текущий пароль");
        if(matches(r.password(),u.passwordHash)) throw new ApiError(400,"Новый пароль должен отличаться от текущего");
        u.passwordHash=hashPassword(r.password()); u.mustChangePassword=false; u.sessionVersion++;
    }
    public void profile(UUID id,Requests.Profile r) { UserAccount u=lockedUser(id); u.name=r.name().trim(); u.phone=r.phone().trim(); }
    public Map<String,Object> twoFactor(UUID id,Requests.TwoFactor r) {
        UserAccount u=lockedUser(id);
        if(!matches(r.password(),u.passwordHash)) throw new ApiError(400,"Неверный текущий пароль");
        Challenge c=issue(u.email,r.enabled()?"ENABLE_2FA":"DISABLE_2FA",codeMinutes); c.sessionVersion=u.sessionVersion;
        return challengeResponse(c);
    }
    public void confirmTwoFactor(UUID id,Requests.Verify r,boolean enabled) {
        UserAccount u=lockedUser(id);
        Challenge c=verify(r.challengeId(),r.code(),enabled?"ENABLE_2FA":"DISABLE_2FA");
        if(!c.email.equals(u.email)||c.sessionVersion!=u.sessionVersion) throw new ApiError(400,"Запрос недействителен");
        u.twoFactor=enabled; u.sessionVersion++;
    }
    public Map<String,Object> changeEmail(UUID id,Requests.ChangeEmail r) {
        UserAccount u=lockedUser(id);
        if(!matches(r.password(),u.passwordHash)) throw new ApiError(400,"Неверный текущий пароль");
        String email=email(r.email());
        if(users.findByEmail(email).isPresent()) throw new ApiError(409,"Этот e-mail уже используется");
        Challenge c=issue(email,"EMAIL",codeMinutes); c.name=id.toString(); c.sessionVersion=u.sessionVersion;
        return challengeResponse(c);
    }
    public void confirmEmail(UUID id,Requests.Verify r) {
        UserAccount u=lockedUser(id); Challenge c=verify(r.challengeId(),r.code(),"EMAIL");
        if(!id.toString().equals(c.name)||u.sessionVersion!=c.sessionVersion) throw new ApiError(400,"Запрос недействителен");
        if(users.findByEmail(c.email).isPresent()) throw new ApiError(409,"Этот e-mail уже используется");
        u.email=c.email; u.sessionVersion++;
    }
    static Map<String,Object> challengeResponse(Challenge c) { return Map.of("challengeId",c.id,"expiresAt",c.expiresAt,"message","Код отправлен на Gmail"); }
    static Map<String,Object> view(UserAccount u) {
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("id",u.id); v.put("name",u.name); v.put("email",u.email); v.put("phone",u.phone); v.put("role",u.role);
        v.put("twoFactorEnabled",u.twoFactor); v.put("mustChangePassword",u.mustChangePassword); v.put("blocked",u.blocked); v.put("establishmentId",u.establishmentId);
        return v;
    }
    public record LoginResult(UserAccount user,Map<String,Object> challenge) {}
}
