package com.example.bygynal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Map;
public class ApiError extends RuntimeException {
    public final int status;
    public ApiError(int status, String message) { super(message); this.status=status; }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(org.springframework.mail.MailException.class)
    ResponseEntity<?> mail(Exception e) { return ResponseEntity.status(503).body(Map.of("message","Не удалось отправить письмо. Попробуйте позже")); }
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> api(ApiError e) { return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField()+": "+f.getDefaultMessage()).findFirst().orElse("Проверьте данные")));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","Неверный формат запроса")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("message","Данные уже используются или нарушают ограничения")); }
}
