package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.api.menu.MenuApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.MenuRepository;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.domain.menu.OfferingWindowRepository;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pickup window CRUD, time validation, and capacity edits. */
@Service
public class PickupWindowService {

    private final MenuRepository menus;
    private final MenuDateRepository dates;
    private final PickupWindowRepository windows;
    private final OfferingWindowRepository offeringWindows;
    private final MenuTimeRules time;
    private final MenuViews views;
    private final AuditService audit;

    public PickupWindowService(MenuRepository menus, MenuDateRepository dates, PickupWindowRepository windows,
            OfferingWindowRepository offeringWindows, MenuTimeRules time, MenuViews views, AuditService audit) {
        this.menus = menus;
        this.dates = dates;
        this.windows = windows;
        this.offeringWindows = offeringWindows;
        this.time = time;
        this.views = views;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MenuApi.PickupWindow> list(UUID dateId) {
        return windows.findAllByMenuDate_IdOrderByStartAtAsc(dateId).stream().map(views::window).toList();
    }

    @Transactional(readOnly = true)
    public MenuApi.PickupWindow get(UUID windowId) {
        return views.window(findWindow(windowId));
    }

    @Transactional
    public MenuApi.PickupWindow create(UUID dateId, MenuApi.PickupWindowWrite request, UUID adminId) {
        MenuDate date = editableDate(dateId);
        validateTimes(request, date);
        Instant now = Instant.now();
        PickupWindow window = new PickupWindow(Uuids.v7(), date, request.label().trim(),
            request.startAt(), request.endAt(), request.maxOrders(), now);
        windows.save(window);
        audit.record(ActorType.ADMIN, adminId, "PICKUP_WINDOW_CREATED", "PICKUP_WINDOW", window.getId(),
            null, Map.of("menuDateId", dateId, "label", window.getLabel()));
        return views.window(window);
    }

    @Transactional
    public MenuApi.PickupWindow update(UUID windowId, MenuApi.PickupWindowWrite request, UUID adminId) {
        PickupWindow window = findWindow(windowId);
        editableDate(window.getMenuDateId());
        validateTimes(request, window.getMenuDate());
        Map<String, Object> before = Map.of("label", window.getLabel(), "maxOrders", window.getMaxOrders());
        window.update(request.label().trim(), request.startAt(), request.endAt(), request.maxOrders(), Instant.now());
        windows.save(window);
        audit.record(ActorType.ADMIN, adminId, "PICKUP_WINDOW_UPDATED", "PICKUP_WINDOW", windowId,
            before, Map.of("label", window.getLabel(), "maxOrders", window.getMaxOrders()));
        return views.window(window);
    }

    @Transactional
    public void delete(UUID windowId, UUID adminId) {
        PickupWindow window = findWindow(windowId);
        editableDate(window.getMenuDateId());
        if (window.getReservedOrders() > 0 || offeringWindows.existsByPickupWindow_Id(windowId)) {
            throw new AuthException(HttpStatus.CONFLICT, "WINDOW_IN_USE",
                "Window has reserved orders or offering assignments and cannot be deleted");
        }
        audit.record(ActorType.ADMIN, adminId, "PICKUP_WINDOW_DELETED", "PICKUP_WINDOW", windowId,
            Map.of("label", window.getLabel()), null);
        windows.delete(window);
    }

    private void validateTimes(MenuApi.PickupWindowWrite request, MenuDate date) {
        if (request.startAt() == null || request.endAt() == null) {
            throw new RequestValidationException("startAt", "startAt and endAt are required");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw new RequestValidationException("endAt", "must be after startAt");
        }
        if (!time.fallsOnBakeDate(request.startAt(), date.getBakeDate())
                || !time.fallsOnBakeDate(request.endAt(), date.getBakeDate())) {
            throw new RequestValidationException("startAt",
                "startAt and endAt must fall on the bake date in " + time.zone());
        }
    }

    private MenuDate editableDate(UUID dateId) {
        MenuDate date = dates.findById(dateId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu date not found"));
        Menu menu = menus.findById(date.getMenuId())
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu not found"));
        if (menu.getStatus() == MenuStatus.CANCELLED) {
            throw new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", "Cancelled menus are read-only");
        }
        if (!date.isActive()) {
            throw new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", "Cancelled dates are read-only");
        }
        return date;
    }

    private PickupWindow findWindow(UUID windowId) {
        return windows.findById(windowId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Pickup window not found"));
    }
}
