package com.example.bygynal;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Duration;
import java.util.*;

/**
 * API кабинета сотрудника заведения (ТЗ 4.3, 4.4). Доступ только для роли SELLER (см. SecurityConfig).
 * Заведение берём из учётной записи сотрудника, а не из запроса: так работать можно только со своим заведением.
 * Формы с фото отправляются как multipart/form-data.
 */
@RestController
@RequestMapping("/api/seller")
public class SellerController {
    private final AuthService auth;
    private final Establishments establishments;
    private final Offers offers;
    private final Images images;

    public SellerController(AuthService auth, Establishments establishments, Offers offers, Images images) {
        this.auth = auth; this.establishments = establishments; this.offers = offers; this.images = images;
    }

    private UUID establishmentId(Authentication a) {
        return auth.user(AuthController.id(a)).establishmentId;
    }

    @GetMapping("/establishment")
    Object establishment(Authentication a) {
        return establishments.find(establishmentId(a)).orElseThrow(() -> new ApiError(404, "Заведение не найдено"));
    }

    @PostMapping("/establishment")
    Object saveEstablishment(Authentication a, Establishments.Form form,
                             @RequestParam(required = false) MultipartFile logo,
                             @RequestParam(defaultValue = "false") boolean removeLogo) {
        establishments.update(establishmentId(a), form, logo, removeLogo);
        return AuthController.message("Сведения сохранены");
    }

    @GetMapping("/offers")
    Object list(Authentication a) { return offers.forEstablishment(establishmentId(a)); }

    @GetMapping("/offers/{id}")
    Object get(Authentication a, @PathVariable UUID id) { return offers.get(establishmentId(a), id); }

    @PostMapping("/offers")
    Object create(Authentication a, Offers.Form form, @RequestParam(required = false) List<MultipartFile> photos) {
        UUID id = offers.create(establishmentId(a), form, photos);
        return Map.of("id", id, "message", "Предложение опубликовано");
    }

    @PostMapping("/offers/{id}")
    Object update(Authentication a, @PathVariable UUID id, Offers.Form form,
                  @RequestParam(required = false) List<MultipartFile> photos) {
        offers.update(establishmentId(a), id, form, photos);
        return AuthController.message("Изменения сохранены");
    }

    /**
     * Логотип и фото наборов своего заведения для страниц кабинета.
     * Покупатели видят фото через каталог (/api/catalog/{id}/image), здесь — только своё заведение.
     */
    @GetMapping("/images/{id}")
    ResponseEntity<byte[]> image(Authentication a, @PathVariable UUID id) {
        if (!establishments.ownsImage(establishmentId(a), id)) return ResponseEntity.notFound().build();
        return images.find(id)
            .map(img -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(img.data()))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/offers/{id}/withdraw")
    Object withdraw(Authentication a, @PathVariable UUID id) {
        offers.withdraw(establishmentId(a), id);
        return AuthController.message("Предложение снято с продажи. Оплаченные заказы будут выданы как обычно");
    }
}

/** Слишком большой файл отсекается ещё до контроллера, поэтому ошибку переводим здесь. */
@RestControllerAdvice
class UploadErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    org.springframework.http.ResponseEntity<?> tooLarge() {
        return org.springframework.http.ResponseEntity.status(413).body(Map.of("message", "Фото должно быть не больше 5 МБ, всего не больше 16 МБ"));
    }
}
