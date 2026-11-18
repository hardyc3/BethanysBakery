package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.api.menu.MenuApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.OfferingWindow;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only public menu queries. Only published menus/dates/offerings are visible. */
@Service
public class PublicMenuService {

    private final MenuDateRepository dates;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;
    private final MenuTimeRules time;
    private final MenuViews views;

    public PublicMenuService(MenuDateRepository dates, PickupWindowRepository windows,
            OfferingRepository offerings, MenuTimeRules time, MenuViews views) {
        this.dates = dates;
        this.windows = windows;
        this.offerings = offerings;
        this.time = time;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public List<MenuApi.PublicMenuDateSummary> list(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? time.today() : from;
        LocalDate end = to == null ? start.plusDays(60) : to;
        return dates.findPublicBetween(start, end).stream()
            .map(date -> {
                List<PickupWindow> activeWindows = activeWindows(date.getId());
                List<Offering> visibleOfferings = visibleOfferings(date.getId(), activeWindows);
                Instant cutoff = activeWindows.stream()
                    .map(window -> time.orderCutoffAt(window.getStartAt()))
                    .min(Instant::compareTo).orElse(null);
                return views.publicSummary(date, activeWindows.size(), visibleOfferings.size(), cutoff);
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public MenuApi.PublicMenuDate get(UUID menuDateId) {
        MenuDate date = dates.findPublicById(menuDateId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu date not found"));
        List<PickupWindow> activeWindows = activeWindows(date.getId());
        List<Offering> visible = visibleOfferings(date.getId(), activeWindows);
        return views.publicDate(date, activeWindows, visible);
    }

    @Transactional(readOnly = true)
    public MenuApi.PublicOffering getOffering(UUID offeringId) {
        Offering offering = offerings.findPublicById(offeringId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Offering not found"));
        return views.publicOffering(offering);
    }

    private List<PickupWindow> activeWindows(UUID dateId) {
        return windows.findAllByMenuDate_IdOrderByStartAtAsc(dateId).stream()
            .filter(PickupWindow::isActive).toList();
    }

    private List<Offering> visibleOfferings(UUID dateId, List<PickupWindow> activeWindows) {
        Set<UUID> activeWindowIds = activeWindows.stream().map(PickupWindow::getId).collect(Collectors.toSet());
        return offerings.findAllByMenuDate_IdOrderByNameAsc(dateId).stream()
            .filter(Offering::isActive)
            .filter(offering -> offering.getWindows().stream()
                .map(OfferingWindow::getPickupWindowId).anyMatch(activeWindowIds::contains))
            .toList();
    }
}
