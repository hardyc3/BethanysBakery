package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.menu.PickupWindowService;
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
public class PickupWindowController {

    private final PickupWindowService service;

    public PickupWindowController(PickupWindowService service) {
        this.service = service;
    }

    @GetMapping("/admin/v1/menu-dates/{menuDateId}/pickup-windows")
    public List<MenuApi.PickupWindow> list(@PathVariable UUID menuDateId) {
        return service.list(menuDateId);
    }

    @PostMapping("/admin/v1/menu-dates/{menuDateId}/pickup-windows")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuApi.PickupWindow create(@PathVariable UUID menuDateId,
            @Valid @RequestBody MenuApi.PickupWindowWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.create(menuDateId, request, principal.adminId());
    }

    @PutMapping("/admin/v1/pickup-windows/{windowId}")
    public MenuApi.PickupWindow update(@PathVariable UUID windowId,
            @Valid @RequestBody MenuApi.PickupWindowWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.update(windowId, request, principal.adminId());
    }

    @DeleteMapping("/admin/v1/pickup-windows/{windowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID windowId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.delete(windowId, principal.adminId());
    }
}
