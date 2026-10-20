package com.example.bygynal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import java.util.*;
import java.time.Instant;
import org.slf4j.LoggerFactory;

@RestController @RequestMapping("/api/admin")
@Transactional
public class AdminController {
    final AuthService auth; final JdbcTemplate jdbc; final Users users;
    public AdminController(AuthService auth,JdbcTemplate jdbc,Users users) {this.auth=auth;this.jdbc=jdbc;this.users=users;}
    void audit(Authentication a,String action,UUID target) {
        UUID actor=AuthController.id(a);
        jdbc.update("insert into admin_audit values (?, ?, ?, ?, ?)",UUID.randomUUID(),actor,action,target,java.sql.Timestamp.from(Instant.now()));
        LoggerFactory.getLogger(getClass()).info("Admin {} action {} target {}",actor,action,target);
    }
    @GetMapping("/users") Object users(@RequestParam(defaultValue="") String search) {
        // Bounded output; never expose hashes or authentication challenges.
        return jdbc.queryForList("select id, name, email, role, blocked, establishment_id from app_users where lower(email) like ? or lower(name) like ? order by email limit 100", "%"+search.toLowerCase(Locale.ROOT)+"%","%"+search.toLowerCase(Locale.ROOT)+"%");
    }
    @GetMapping("/establishments") Object establishments() { return jdbc.queryForList("select * from establishments order by name limit 100"); }
    @PostMapping("/establishments") Object establishment(Authentication a,@Valid @RequestBody Requests.Establishment r) {
        UUID id=UUID.randomUUID(); jdbc.update("insert into establishments values (?, ?, ?)",id,r.name().trim(),r.address().trim());
        audit(a,"CREATE_ESTABLISHMENT",id); return Map.of("id",id,"message","Заведение создано");
    }
    @PostMapping("/sellers") Object seller(Authentication a,@Valid @RequestBody Requests.Seller r) {
        String email=AuthService.email(r.email());
        if(users.findByEmail(email).isPresent()) throw new ApiError(409,"Этот e-mail уже используется");
        if(jdbc.queryForObject("select count(*) from establishments where id = ?",Integer.class,r.establishmentId())==0) throw new ApiError(400,"Заведение не найдено");
        String temporary="Aa1!"+UUID.randomUUID();
        UserAccount u=new UserAccount(); u.email=email; u.name=r.name().trim(); u.phone=r.phone();
        u.role="SELLER"; u.establishmentId=r.establishmentId(); u.mustChangePassword=true; u.passwordHash=auth.hashPassword(temporary);
        users.saveAndFlush(u);
        auth.send(email,"Учётная запись сотрудника","Администратор создал вашу учётную запись.\nЛогин: "+email+"\nВременный пароль: "+temporary+"\nПри первом входе обязательно смените пароль.");
        audit(a,"CREATE_SELLER",u.id); return Map.of("id",u.id,"message","Сотрудник создан. Временный пароль отправлен на Gmail");
    }
    @PatchMapping("/users/{id}/block") Object block(Authentication a,@PathVariable UUID id,@Valid @RequestBody Requests.Block r) {
        if(id.equals(AuthController.id(a))) throw new ApiError(400,"Нельзя заблокировать себя");
        UserAccount u=auth.lockedUser(id); u.blocked=r.blocked(); u.sessionVersion++;
        audit(a,r.blocked()?"BLOCK_USER":"UNBLOCK_USER",id); return AuthController.message("Статус обновлён");
    }
}
