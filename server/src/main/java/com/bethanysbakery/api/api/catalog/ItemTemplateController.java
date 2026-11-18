package com.bethanysbakery.api.api.catalog;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.catalog.ItemTemplateService;
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
@RequestMapping("/admin/v1/item-templates")
public class ItemTemplateController {

    private final ItemTemplateService service;

    public ItemTemplateController(ItemTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<CatalogApi.ItemTemplate> list(
            @RequestParam(required = false) Boolean active) {
        return service.list(active);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogApi.ItemTemplate create(@Valid @RequestBody CatalogApi.ItemTemplateWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.create(request, principal.adminId());
    }

    @GetMapping("/{templateId}")
    public CatalogApi.ItemTemplate get(@PathVariable UUID templateId) {
        return service.get(templateId);
    }

    @PutMapping("/{templateId}")
    public CatalogApi.ItemTemplate replace(@PathVariable UUID templateId,
            @Valid @RequestBody CatalogApi.ItemTemplateWrite request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.replace(templateId, request, principal.adminId());
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID templateId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.deactivate(templateId, principal.adminId());
    }
}
