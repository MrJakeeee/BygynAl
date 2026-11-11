package com.example.bygynal;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<CatalogService.Offer> offers(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "") String type,
        @RequestParam(required = false) BigDecimal min,
        @RequestParam(required = false) BigDecimal max,
        @RequestParam(required = false) LocalTime until,
        @RequestParam(defaultValue = "time") String sort) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0 ||
            min != null && max != null && min.compareTo(max) > 0) {
            throw new ApiError(400, "Проверьте диапазон цены");
        }
        return catalog.search(q, type, min, max, until, sort);
    }

    @GetMapping("/types")
    public List<String> categories() {
        return catalog.categories();
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        CatalogService.Photo photo = catalog.photo(id);
        if (photo == null) return ResponseEntity.notFound().build();
        MediaType mediaType = MediaType.parseMediaType(photo.contentType());
        return ResponseEntity.ok().contentType(mediaType).cacheControl(CacheControl.noCache()).body(photo.data());
    }
}
