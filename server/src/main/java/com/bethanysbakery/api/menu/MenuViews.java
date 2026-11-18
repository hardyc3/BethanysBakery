package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.api.menu.MenuApi;
import com.bethanysbakery.api.catalog.MediaViews;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.catalog.Media;
import com.bethanysbakery.api.domain.catalog.MediaRepository;
import com.bethanysbakery.api.domain.catalog.MediaStatus;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingOption;
import com.bethanysbakery.api.domain.menu.OfferingOptionGroup;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serialises S4 entities into admin and public API records. */
@Component
public class MenuViews {

    private final MediaRepository mediaRepository;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;
    private final BakeryProperties properties;
    private final MenuTimeRules time;

    public MenuViews(MediaRepository mediaRepository, PickupWindowRepository windows,
            OfferingRepository offerings, BakeryProperties properties, MenuTimeRules time) {
        this.mediaRepository = mediaRepository;
        this.windows = windows;
        this.offerings = offerings;
        this.properties = properties;
        this.time = time;
    }

    public MenuApi.MenuSummary summary(Menu menu, List<MenuDate> dates) {
        List<LocalDate> bakeDates = dates.stream().map(MenuDate::getBakeDate).toList();
        return new MenuApi.MenuSummary(menu.getId(), menu.getTitle(), menu.getStatus(),
            bakeDates.isEmpty() ? null : bakeDates.get(0), bakeDates,
            menu.getPublishedAt(), menu.getUpdatedAt());
    }

    /** Full menu with every date expanded to its windows and offerings (contract: Menu.dates are MenuDate). */
    public MenuApi.Menu menu(Menu menu, List<MenuDate> dates) {
        return new MenuApi.Menu(menu.getId(), menu.getTitle(), menu.getNotes(), menu.getStatus(),
            menu.isNotificationEnabled(), menu.getNotifyAt(), menu.getNotifySentAt(), menu.getPublishedAt(),
            menu.getCancelledAt(), menu.getCancellationReason(),
            dates.stream().map(this::dateWithChildren).toList(),
            menu.getVersion(), menu.getCreatedAt(), menu.getUpdatedAt());
    }

    private MenuApi.MenuDate dateWithChildren(MenuDate date) {
        return date(date,
            windows.findAllByMenuDate_IdOrderByStartAtAsc(date.getId()),
            offerings.findAllByMenuDate_IdOrderByNameAsc(date.getId()));
    }

    public MenuApi.MenuDate date(MenuDate date, List<PickupWindow> windows, List<Offering> offerings) {
        return new MenuApi.MenuDate(date.getId(), date.getMenuId(), date.getBakeDate(), date.getStatus(),
            date.getReminderSentAt(), date.getCancelledAt(), date.getCancellationReason(),
            windows.stream().map(this::window).toList(),
            offerings.stream().map(this::offering).toList(), date.getVersion());
    }

    public MenuApi.PickupWindow window(PickupWindow window) {
        return new MenuApi.PickupWindow(window.getId(), window.getMenuDateId(), window.getLabel(),
            window.getStartAt(), window.getEndAt(), window.getMaxOrders(), window.getReservedOrders(),
            window.availableCapacity(), time.orderCutoffAt(window.getStartAt()),
            window.getStatus(), window.getVersion());
    }

    public MenuApi.Offering offering(Offering offering) {
        return new MenuApi.Offering(offering.getId(), offering.getMenuDateId(), offering.getItemTemplateId(),
            offering.getName(), offering.getDescription(), photoView(offering.getPhotoMediaId()),
            offering.getIngredientsText(), offering.getAllergenTags(), offering.getPriceCents(),
            offering.getQuantityLimit(), offering.getReservedCount(), offering.availableQuantity(),
            offering.getMaxPerCustomer(), offering.getStatus(), windowIds(offering),
            offering.getGroups().stream().map(this::group).toList(), offering.getVersion());
    }

    public MenuApi.PublicMenuDateSummary publicSummary(MenuDate date, int windowCount, int offeringCount,
            Instant orderCutoffAt) {
        return new MenuApi.PublicMenuDateSummary(date.getId(), date.getBakeDate(), date.getStatus(),
            windowCount, offeringCount, orderCutoffAt);
    }

    public MenuApi.PublicMenuDate publicDate(MenuDate date, List<PickupWindow> windows, List<Offering> offerings) {
        return new MenuApi.PublicMenuDate(date.getId(), date.getMenuId(), date.getMenu().getTitle(),
            date.getMenu().getNotes(), date.getBakeDate(), date.getStatus(),
            windows.stream().map(this::publicWindow).toList(),
            offerings.stream().map(this::publicOffering).toList());
    }

    private MenuApi.PublicPickupWindow publicWindow(PickupWindow window) {
        return new MenuApi.PublicPickupWindow(window.getId(), window.getLabel(), window.getStartAt(),
            window.getEndAt(), window.availableCapacity(), time.orderCutoffAt(window.getStartAt()));
    }

    public MenuApi.PublicOffering publicOffering(Offering offering) {
        Map<String, String> variants = new LinkedHashMap<>();
        String photoUrl = null;
        if (offering.getPhotoMediaId() != null) {
            Media media = activeMedia(offering.getPhotoMediaId());
            if (media != null) {
                String base = properties.auth().appPublicBaseUrl();
                photoUrl = MediaViews.absoluteUrl(base, media.getId(), null);
                media.getVariants().keySet()
                    .forEach(key -> variants.put(key, MediaViews.absoluteUrl(base, media.getId(), key)));
            }
        }
        int available = offering.availableQuantity();
        return new MenuApi.PublicOffering(offering.getId(), offering.getName(), offering.getDescription(),
            photoUrl, variants, offering.getPriceCents(), available, available == 0,
            offering.getIngredientsText(), offering.getAllergenTags(), windowIds(offering),
            offering.getGroups().stream().map(this::publicGroup).toList());
    }

    private MenuApi.PublicCustomizationGroup publicGroup(OfferingOptionGroup group) {
        return new MenuApi.PublicCustomizationGroup(group.getId(), group.getName(), group.getSelectionType(),
            group.isRequired(), group.getMinSelections(), group.getMaxSelections(),
            group.getOptions().stream().map(this::publicOption).toList());
    }

    private MenuApi.PublicCustomizationOption publicOption(OfferingOption option) {
        return new MenuApi.PublicCustomizationOption(option.getId(), option.getName(),
            option.getPriceDeltaCents(), option.isAvailable());
    }

    private MenuApi.OfferingOptionGroup group(OfferingOptionGroup group) {
        return new MenuApi.OfferingOptionGroup(group.getId(), group.getName(), group.getSelectionType(),
            group.isRequired(), group.getMinSelections(), group.getMaxSelections(), group.getSortOrder(),
            group.getOptions().stream().map(this::option).toList());
    }

    private MenuApi.OfferingOption option(OfferingOption option) {
        return new MenuApi.OfferingOption(option.getId(), option.getName(), option.getPriceDeltaCents(),
            option.isAvailable(), option.getSortOrder());
    }

    private List<UUID> windowIds(Offering offering) {
        return offering.getWindows().stream().map(w -> w.getPickupWindowId()).toList();
    }

    private com.bethanysbakery.api.api.catalog.CatalogApi.MediaView photoView(UUID mediaId) {
        if (mediaId == null) return null;
        Media media = activeMedia(mediaId);
        return media == null ? null : MediaViews.of(media);
    }

    private Media activeMedia(UUID mediaId) {
        return mediaRepository.findByIdAndStatus(mediaId, MediaStatus.ACTIVE).orElse(null);
    }
}
