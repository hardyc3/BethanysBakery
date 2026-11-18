package com.bethanysbakery.api.api.menu;

import com.bethanysbakery.api.api.catalog.CatalogApi;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import com.bethanysbakery.api.domain.menu.ActiveStatus;
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MenuApi {

    private MenuApi() {}

    public record MenuSummary(UUID id, String title, MenuStatus status, LocalDate firstBakeDate,
            List<LocalDate> dates, Instant publishedAt, Instant updatedAt) {}

    public record Menu(UUID id, String title, String notes, MenuStatus status, boolean notificationEnabled,
            Instant notifyAt, Instant notifySentAt, Instant publishedAt, Instant cancelledAt,
            String cancellationReason, List<MenuDate> dates, int version, Instant createdAt, Instant updatedAt) {}

    public record MenuWrite(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 2000) String notes
    ) {}

    public record PublishMenuRequest(
        @NotNull Boolean notificationEnabled,
        Instant notifyAt
    ) {}

    public record CancelRequest(@Size(max = 500) String reason) {}

    public record MenuDate(UUID id, UUID menuId, LocalDate bakeDate, MenuDateStatus status,
            Instant reminderSentAt, Instant cancelledAt, String cancellationReason,
            List<PickupWindow> pickupWindows, List<Offering> offerings, int version) {}

    public record MenuDateWrite(@NotNull LocalDate bakeDate) {}

    public record PickupWindow(UUID id, UUID menuDateId, String label, Instant startAt, Instant endAt,
            int maxOrders, int reservedOrders, int availableCapacity, Instant orderCutoffAt,
            ActiveStatus status, int version) {}

    public record PickupWindowWrite(
        @NotBlank @Size(max = 60) String label,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull @Min(1) Integer maxOrders
    ) {}

    public record Offering(UUID id, UUID menuDateId, UUID itemTemplateId, String name, String description,
            CatalogApi.MediaView photo, String ingredientsText, List<String> allergenTags,
            int priceCents, int quantityLimit, int reservedCount, int availableQuantity,
            Integer maxPerCustomer, ActiveStatus status, List<UUID> windowIds,
            List<OfferingOptionGroup> customizationGroups, int version) {}

    public record OfferingWrite(
        @NotNull UUID itemTemplateId,
        @Size(max = 120) String name,
        @Size(max = 2000) String description,
        UUID photoMediaId,
        @Size(max = 4000) String ingredientsText,
        @Size(max = 20) List<@NotBlank @Size(max = 60) String> allergenTags,
        @NotNull @Min(0) @Max(99999) Integer priceCents,
        @NotNull @Min(1) @Max(9999) Integer quantityLimit,
        @Min(1) @Max(9999) Integer maxPerCustomer,
        @NotEmpty List<UUID> windowIds,
        @Valid @Size(max = 20) List<@Valid OfferingOptionGroupWrite> customizationGroups
    ) {}

    public record OfferingOptionGroup(UUID id, String name, SelectionType selectionType, boolean required,
            int minSelections, int maxSelections, int sortOrder, List<OfferingOption> options) {}

    public record OfferingOptionGroupWrite(
        UUID id,
        @NotBlank @Size(max = 80) String name,
        @NotNull SelectionType selectionType,
        @NotNull Boolean required,
        @NotNull @Min(0) Integer minSelections,
        @NotNull @Min(1) Integer maxSelections,
        Integer sortOrder,
        @Valid @NotEmpty @Size(max = 50) List<@Valid OfferingOptionWrite> options
    ) {}

    public record OfferingOption(UUID id, String name, int priceDeltaCents, boolean available, int sortOrder) {}

    public record OfferingOptionWrite(
        UUID id,
        @NotBlank @Size(max = 80) String name,
        @NotNull @Min(0) Integer priceDeltaCents,
        Boolean available,
        Integer sortOrder
    ) {}

    public record PublicMenuDateSummary(UUID id, LocalDate bakeDate, MenuDateStatus status, int windowCount,
            int offeringCount, Instant orderCutoffAt) {}

    public record PublicMenuDate(UUID id, UUID menuId, String title, String notes, LocalDate bakeDate,
            MenuDateStatus status, List<PublicPickupWindow> pickupWindows, List<PublicOffering> offerings) {}

    public record PublicPickupWindow(UUID id, String label, Instant startAt, Instant endAt,
            int availableCapacity, Instant orderCutoffAt) {}

    public record PublicOffering(UUID id, String name, String description, String photoUrl,
            Map<String, String> photoVariants, int priceCents, int availableQuantity, boolean soldOut,
            String ingredientsText, List<String> allergenTags, List<UUID> windowIds,
            List<PublicCustomizationGroup> customizationGroups) {}

    public record PublicCustomizationGroup(UUID id, String name, SelectionType selectionType, boolean required,
            int minSelections, int maxSelections, List<PublicCustomizationOption> options) {}

    public record PublicCustomizationOption(UUID id, String name, int priceDeltaCents, boolean available) {}
}
