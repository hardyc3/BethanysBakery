package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.menu.OfferingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class OfferingController {

    private final OfferingService service;

    public OfferingController(OfferingService service) {
        this.service = service;
    }

    @GetMapping("/admin/v1/menu-dates/{menuDateId}/offerings")
    public List<MenuApi.Offering> list(@PathVariable UUID menuDateId) {
        return service.list(menuDateId);
    }

    @PostMapping("/admin/v1/menu-dates/{menuDateId}/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuApi.Offering create(@PathVariable UUID menuDateId, @Valid @RequestBody MenuApi.OfferingWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.create(menuDateId, request, principal.adminId());
    }

    @GetMapping("/admin/v1/offerings/{offeringId}")
    public MenuApi.Offering get(@PathVariable UUID offeringId) {
        return service.get(offeringId);
    }

    @PutMapping("/admin/v1/offerings/{offeringId}")
    public MenuApi.Offering update(@PathVariable UUID offeringId, @Valid @RequestBody MenuApi.OfferingWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.update(offeringId, request, principal.adminId());
    }

    @DeleteMapping("/admin/v1/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID offeringId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.cancel(offeringId, null, principal.adminId());
    }
}
