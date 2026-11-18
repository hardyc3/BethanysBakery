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
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.MenuRepository;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin menu and bake-date lifecycle: CRUD, publish, and cancel. */
@Service
public class MenuService {

    private final MenuRepository menus;
    private final MenuDateRepository dates;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;
    private final MenuPublishValidator publishValidator;
    private final MenuTimeRules time;
    private final MenuViews views;
    private final AuditService audit;
    private final List<MenuCancellationHook> cancellationHooks;

    public MenuService(MenuRepository menus, MenuDateRepository dates, PickupWindowRepository windows,
            OfferingRepository offerings, MenuPublishValidator publishValidator, MenuTimeRules time,
            MenuViews views, AuditService audit, List<MenuCancellationHook> cancellationHooks) {
        this.menus = menus;
        this.dates = dates;
        this.windows = windows;
        this.offerings = offerings;
        this.publishValidator = publishValidator;
        this.time = time;
        this.views = views;
        this.audit = audit;
        this.cancellationHooks = cancellationHooks;
    }

    @Transactional(readOnly = true)
    public List<MenuApi.MenuSummary> list(MenuStatus status) {
        List<Menu> found = status == null
            ? menus.findAllByOrderByCreatedAtDesc()
            : menus.findAllByStatusOrderByCreatedAtDesc(status);
        return found.stream()
            .map(menu -> views.summary(menu, dates.findAllByMenu_IdOrderByBakeDateAsc(menu.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public MenuApi.Menu get(UUID menuId) {
        Menu menu = findMenu(menuId);
        return menuView(menu);
    }

    @Transactional
    public MenuApi.Menu create(MenuApi.MenuWrite request, UUID adminId) {
        Instant now = Instant.now();
        Menu menu = new Menu(Uuids.v7(), request.title().trim(), trimToNull(request.notes()), adminId, now);
        menus.save(menu);
        audit.record(ActorType.ADMIN, adminId, "MENU_CREATED", "MENU", menu.getId(), null, summaryMap(menu));
        return views.menu(menu, List.of());
    }

    @Transactional
    public MenuApi.Menu update(UUID menuId, MenuApi.MenuWrite request, UUID adminId) {
        Menu menu = findMenu(menuId);
        requireNotCancelled(menu);
        Map<String, Object> before = summaryMap(menu);
        menu.update(request.title().trim(), trimToNull(request.notes()), Instant.now());
        menus.save(menu);
        audit.record(ActorType.ADMIN, adminId, "MENU_UPDATED", "MENU", menuId, before, summaryMap(menu));
        return menuView(menu);
    }

    @Transactional
    public void deleteDraft(UUID menuId, UUID adminId) {
        Menu menu = findMenu(menuId);
        if (menu.getStatus() != MenuStatus.DRAFT) {
            throw invalidState("Only draft menus can be deleted; cancel published menus instead");
        }
        audit.record(ActorType.ADMIN, adminId, "MENU_DELETED", "MENU", menuId, summaryMap(menu), null);
        menus.delete(menu);
    }

    @Transactional
    public MenuApi.Menu publish(UUID menuId, MenuApi.PublishMenuRequest request, UUID adminId) {
        Menu menu = findMenu(menuId);
        if (menu.getStatus() != MenuStatus.DRAFT) {
            throw invalidState("Only draft menus can be published");
        }
        List<MenuDate> menuDates = dates.findAllByMenu_IdOrderByBakeDateAsc(menuId);
        publishValidator.validate(menu, menuDates);

        Instant now = Instant.now();
        Instant candidate = request.notifyAt() != null
            ? request.notifyAt()
            : time.defaultNotifyAt(menuDates.stream().filter(MenuDate::isActive)
                .findFirst().orElseThrow().getBakeDate());
        Instant notifyAt = candidate.isBefore(now) ? now : candidate;

        Map<String, Object> before = summaryMap(menu);
        menu.publish(Boolean.TRUE.equals(request.notificationEnabled()), notifyAt, now);
        menuDates.stream().filter(MenuDate::isActive).forEach(date -> date.publish(now));
        menus.save(menu);
        audit.record(ActorType.ADMIN, adminId, "MENU_PUBLISHED", "MENU", menuId, before, summaryMap(menu));
        return views.menu(menu, menuDates);
    }

    @Transactional
    public MenuApi.Menu cancel(UUID menuId, String reason, UUID adminId) {
        Menu menu = findMenu(menuId);
        if (menu.getStatus() == MenuStatus.CANCELLED) {
            throw invalidState("Menu is already cancelled");
        }
        Instant now = Instant.now();
        Map<String, Object> before = summaryMap(menu);
        menu.cancel(trimToNull(reason), now);
        for (MenuDate date : dates.findAllByMenu_IdOrderByBakeDateAsc(menuId)) {
            if (date.isActive()) cancelDateAndChildren(date, reason, now);
        }
        menus.save(menu);
        audit.record(ActorType.ADMIN, adminId, "MENU_CANCELLED", "MENU", menuId, before, summaryMap(menu));
        return menuView(menu);
    }

    @Transactional(readOnly = true)
    public MenuApi.MenuDate getDate(UUID dateId) {
        MenuDate date = findDate(dateId);
        return dateView(date);
    }

    @Transactional
    public MenuApi.MenuDate addDate(UUID menuId, MenuApi.MenuDateWrite request, UUID adminId) {
        Menu menu = findMenu(menuId);
        if (menu.getStatus() == MenuStatus.CANCELLED) {
            throw invalidState("Cannot add a date to a cancelled menu");
        }
        if (request.bakeDate() == null || request.bakeDate().isBefore(time.today())) {
            throw new RequestValidationException("bakeDate", "must be today or later in " + time.zone());
        }
        if (dates.findAllByMenu_IdOrderByBakeDateAsc(menuId).stream()
                .anyMatch(existing -> existing.getBakeDate().equals(request.bakeDate()))) {
            throw new RequestValidationException("bakeDate", "a date with this bakeDate already exists");
        }
        Instant now = Instant.now();
        MenuDate date = new MenuDate(Uuids.v7(), menu, request.bakeDate(), now);
        if (menu.getStatus() == MenuStatus.PUBLISHED) {
            date.publish(now);
        }
        dates.save(date);
        audit.record(ActorType.ADMIN, adminId, "MENU_DATE_CREATED", "MENU_DATE", date.getId(),
            null, Map.of("menuId", menuId, "bakeDate", date.getBakeDate().toString()));
        return views.date(date, List.of(), List.of());
    }

    @Transactional
    public MenuApi.MenuDate updateDate(UUID dateId, MenuApi.MenuDateWrite request, UUID adminId) {
        MenuDate date = findDate(dateId);
        if (!date.isActive()) {
            throw invalidState("Only draft/published dates can be edited");
        }
        if (date.getStatus() != MenuDateStatus.DRAFT) {
            throw invalidState("Only draft dates can have their bake date changed; cancel instead");
        }
        if (request.bakeDate() == null || request.bakeDate().isBefore(time.today())) {
            throw new RequestValidationException("bakeDate", "must be today or later in " + time.zone());
        }
        if (dates.findAllByMenu_IdOrderByBakeDateAsc(date.getMenuId()).stream()
                .anyMatch(existing -> !existing.getId().equals(dateId)
                    && existing.getBakeDate().equals(request.bakeDate()))) {
            throw new RequestValidationException("bakeDate", "a date with this bakeDate already exists");
        }
        // Existing pickup windows keep their instants; moving the bake date would orphan them on the
        // old calendar day and desynchronise public order cutoffs. Require them to fall on the new date.
        if (windows.findAllByMenuDate_IdOrderByStartAtAsc(dateId).stream().anyMatch(window ->
                !time.fallsOnBakeDate(window.getStartAt(), request.bakeDate())
                    || !time.fallsOnBakeDate(window.getEndAt(), request.bakeDate()))) {
            throw new RequestValidationException("bakeDate",
                "existing pickup windows must fall on the new bake date; reschedule or remove them first");
        }
        LocalDate before = date.getBakeDate();
        date.update(request.bakeDate(), Instant.now());
        dates.save(date);
        audit.record(ActorType.ADMIN, adminId, "MENU_DATE_UPDATED", "MENU_DATE", dateId,
            Map.of("bakeDate", before.toString()), Map.of("bakeDate", date.getBakeDate().toString()));
        return dateView(date);
    }

    @Transactional
    public void deleteDraftDate(UUID dateId, UUID adminId) {
        MenuDate date = findDate(dateId);
        if (date.getStatus() != MenuDateStatus.DRAFT) {
            throw invalidState("Only draft dates can be deleted; cancel published dates instead");
        }
        audit.record(ActorType.ADMIN, adminId, "MENU_DATE_DELETED", "MENU_DATE", dateId,
            Map.of("bakeDate", date.getBakeDate().toString()), null);
        dates.delete(date);
    }

    @Transactional
    public MenuApi.MenuDate cancelDate(UUID dateId, String reason, UUID adminId) {
        MenuDate date = findDate(dateId);
        if (!date.isActive()) {
            throw invalidState("Date is already cancelled");
        }
        MenuDateStatus before = date.getStatus();
        cancelDateAndChildren(date, reason, Instant.now());
        audit.record(ActorType.ADMIN, adminId, "MENU_DATE_CANCELLED", "MENU_DATE", dateId,
            Map.of("status", before.name()), cancellationDetail("CANCELLED", reason));
        return dateView(date);
    }

    private void cancelDateAndChildren(MenuDate date, String reason, Instant now) {
        date.cancel(trimToNull(reason), now);
        windows.findAllByMenuDate_IdOrderByStartAtAsc(date.getId()).forEach(window -> window.cancel(now));
        offerings.findAllByMenuDate_IdOrderByNameAsc(date.getId()).forEach(offering -> offering.cancel(now));
        dates.save(date);
        for (MenuCancellationHook hook : cancellationHooks) {
            hook.onMenuDateCancelled(date.getId());
        }
    }

    private MenuApi.Menu menuView(Menu menu) {
        return views.menu(menu, dates.findAllByMenu_IdOrderByBakeDateAsc(menu.getId()));
    }

    private MenuApi.MenuDate dateView(MenuDate date) {
        return views.date(date,
            windows.findAllByMenuDate_IdOrderByStartAtAsc(date.getId()),
            offerings.findAllByMenuDate_IdOrderByNameAsc(date.getId()));
    }

    private Menu findMenu(UUID menuId) {
        return menus.findById(menuId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu not found"));
    }

    private MenuDate findDate(UUID dateId) {
        return dates.findById(dateId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu date not found"));
    }

    private void requireNotCancelled(Menu menu) {
        if (menu.getStatus() == MenuStatus.CANCELLED) {
            throw invalidState("Cancelled menus are read-only");
        }
    }

    private static AuthException invalidState(String detail) {
        return new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", detail);
    }

    private static Map<String, Object> summaryMap(Menu menu) {
        return Map.of("title", menu.getTitle(), "status", menu.getStatus().name());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    /** Null-tolerant audit detail: omit the optional reason instead of passing null to Map.of. */
    private static Map<String, Object> cancellationDetail(String status, String reason) {
        String trimmed = trimToNull(reason);
        return trimmed == null ? Map.of("status", status) : Map.of("status", status, "reason", trimmed);
    }
}
