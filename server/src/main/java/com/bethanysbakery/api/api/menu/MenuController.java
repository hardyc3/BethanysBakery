package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.menu.MenuService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/menus")
public class MenuController {

    private final MenuService service;

    public MenuController(MenuService service) {
        this.service = service;
    }

    @GetMapping
    public List<MenuApi.MenuSummary> list(@RequestParam(required = false) MenuStatus status) {
        return service.list(status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MenuApi.Menu create(@Valid @RequestBody MenuApi.MenuWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.create(request, principal.adminId());
    }

    @GetMapping("/{menuId}")
    public MenuApi.Menu get(@PathVariable UUID menuId) {
        return service.get(menuId);
    }

    @PutMapping("/{menuId}")
    public MenuApi.Menu update(@PathVariable UUID menuId, @Valid @RequestBody MenuApi.MenuWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.update(menuId, request, principal.adminId());
    }

    @DeleteMapping("/{menuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable UUID menuId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.deleteDraft(menuId, principal.adminId());
    }

    @PostMapping("/{menuId}/publish")
    public MenuApi.Menu publish(@PathVariable UUID menuId, @Valid @RequestBody MenuApi.PublishMenuRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.publish(menuId, request, principal.adminId());
    }

    @PostMapping("/{menuId}/cancel")
    public MenuApi.Menu cancel(@PathVariable UUID menuId,
            @Valid @RequestBody(required = false) MenuApi.CancelRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.cancel(menuId, request == null ? null : request.reason(), principal.adminId());
    }
}
