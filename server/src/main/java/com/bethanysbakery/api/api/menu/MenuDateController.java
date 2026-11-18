package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.menu.MenuService;
import jakarta.validation.Valid;
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
public class MenuDateController {

    private final MenuService service;

    public MenuDateController(MenuService service) {
        this.service = service;
    }

    @PostMapping("/admin/v1/menus/{menuId}/dates")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuApi.MenuDate addDate(@PathVariable UUID menuId, @Valid @RequestBody MenuApi.MenuDateWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.addDate(menuId, request, principal.adminId());
    }

    @GetMapping("/admin/v1/menu-dates/{menuDateId}")
    public MenuApi.MenuDate get(@PathVariable UUID menuDateId) {
        return service.getDate(menuDateId);
    }

    @PutMapping("/admin/v1/menu-dates/{menuDateId}")
    public MenuApi.MenuDate update(@PathVariable UUID menuDateId, @Valid @RequestBody MenuApi.MenuDateWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.updateDate(menuDateId, request, principal.adminId());
    }

    @DeleteMapping("/admin/v1/menu-dates/{menuDateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable UUID menuDateId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.deleteDraftDate(menuDateId, principal.adminId());
    }

    @PostMapping("/admin/v1/menu-dates/{menuDateId}/cancel")
    public MenuApi.MenuDate cancel(@PathVariable UUID menuDateId,
            @Valid @RequestBody(required = false) MenuApi.CancelRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.cancelDate(menuDateId, request == null ? null : request.reason(), principal.adminId());
    }
}
