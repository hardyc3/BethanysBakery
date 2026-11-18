package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.menu.PublicMenuService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only menu endpoints. Availability must never be cached by shared caches. */
@RestController
@RequestMapping("/api/v1")
public class PublicMenuController {

    /** Availability is user-/time-sensitive; forbid shared caching and browser reuse. */
    private static final String NO_STORE = "private, no-store, max-age=0";

    private final PublicMenuService service;

    public PublicMenuController(PublicMenuService service) {
        this.service = service;
    }

    @GetMapping("/menu-dates")
    public ResponseEntity<List<MenuApi.PublicMenuDateSummary>> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return noStore(service.list(from, to));
    }

    @GetMapping("/menu-dates/{menuDateId}")
    public ResponseEntity<MenuApi.PublicMenuDate> get(@PathVariable UUID menuDateId) {
        return noStore(service.get(menuDateId));
    }

    @GetMapping("/offerings/{offeringId}")
    public ResponseEntity<MenuApi.PublicOffering> getOffering(@PathVariable UUID offeringId) {
        return noStore(service.getOffering(offeringId));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", NO_STORE).body(body);
    }
}
