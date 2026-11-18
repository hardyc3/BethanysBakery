package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.api.menu.MenuApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.catalog.MediaReferenceService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.common.error.Problem.FieldError;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.catalog.CustomizationGroup;
import com.bethanysbakery.api.domain.catalog.CustomizationOption;
import com.bethanysbakery.api.domain.catalog.ItemTemplate;
import com.bethanysbakery.api.domain.catalog.ItemTemplateRepository;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.MenuRepository;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingOption;
import com.bethanysbakery.api.domain.menu.OfferingOptionGroup;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Offering CRUD with template snapshots, customization snapshots, and media-reference safety. */
@Service
public class OfferingService {

    private final MenuRepository menus;
    private final MenuDateRepository dates;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;
    private final ItemTemplateRepository templates;
    private final MediaReferenceService mediaReferences;
    private final MenuViews views;
    private final AuditService audit;

    public OfferingService(MenuRepository menus, MenuDateRepository dates, PickupWindowRepository windows,
            OfferingRepository offerings, ItemTemplateRepository templates, MediaReferenceService mediaReferences,
            MenuViews views, AuditService audit) {
        this.menus = menus;
        this.dates = dates;
        this.windows = windows;
        this.offerings = offerings;
        this.templates = templates;
        this.mediaReferences = mediaReferences;
        this.views = views;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MenuApi.Offering> list(UUID dateId) {
        return offerings.findAllByMenuDate_IdOrderByNameAsc(dateId).stream().map(views::offering).toList();
    }

    @Transactional(readOnly = true)
    public MenuApi.Offering get(UUID offeringId) {
        return views.offering(findOffering(offeringId));
    }

    @Transactional
    public MenuApi.Offering create(UUID dateId, MenuApi.OfferingWrite request, UUID adminId) {
        MenuDate date = editableDate(dateId);
        ItemTemplate template = activeTemplate(request.itemTemplateId());
        if (offerings.existsByMenuDate_IdAndItemTemplateId(dateId, template.getId())) {
            throw new RequestValidationException("itemTemplateId",
                "an offering for this template already exists on this bake date");
        }

        Resolved resolved = resolve(request, template, null);
        if (resolved.photoMediaId != null) mediaReferences.requireActive(resolved.photoMediaId);
        List<PickupWindow> assigned = validateWindows(dateId, request.windowIds(), null);

        Instant now = Instant.now();
        Offering offering = new Offering(Uuids.v7(), date, template.getId(), resolved.name, resolved.description,
            resolved.photoMediaId, resolved.ingredientsText, resolved.allergenTags,
            resolved.priceCents, resolved.quantityLimit, resolved.maxPerCustomer, now);
        offering.replaceGroups(resolved.groups);
        offering.replaceWindows(assigned);
        offerings.save(offering);
        audit.record(ActorType.ADMIN, adminId, "OFFERING_CREATED", "OFFERING", offering.getId(),
            null, Map.of("menuDateId", dateId, "itemTemplateId", template.getId()));
        return views.offering(offering);
    }

    @Transactional
    public MenuApi.Offering update(UUID offeringId, MenuApi.OfferingWrite request, UUID adminId) {
        Offering offering = findOffering(offeringId);
        UUID dateId = offering.getMenuDateId();
        editableDate(dateId);
        if (!request.itemTemplateId().equals(offering.getItemTemplateId())) {
            throw new RequestValidationException("itemTemplateId", "itemTemplateId is immutable after creation");
        }
        // Template data is a creation-time source only. On update, null snapshot fields keep the
        // stored offering values; consulting the template here would leak later template edits.
        Resolved resolved = resolve(request, null, offering);
        if (resolved.photoMediaId != null
                && !resolved.photoMediaId.equals(offering.getPhotoMediaId())) {
            mediaReferences.requireActive(resolved.photoMediaId);
        }
        List<PickupWindow> assigned = validateWindows(dateId, request.windowIds(), offeringId);

        Map<String, Object> before = Map.of("priceCents", offering.getPriceCents(),
            "quantityLimit", offering.getQuantityLimit());
        offering.update(resolved.name, resolved.description, resolved.photoMediaId,
            resolved.ingredientsText, resolved.allergenTags, resolved.priceCents,
            resolved.quantityLimit, resolved.maxPerCustomer, Instant.now());
        offering.replaceGroups(resolved.groups);
        offering.replaceWindows(assigned);
        offerings.save(offering);
        audit.record(ActorType.ADMIN, adminId, "OFFERING_UPDATED", "OFFERING", offeringId,
            before, Map.of("priceCents", offering.getPriceCents(), "quantityLimit", offering.getQuantityLimit()));
        return views.offering(offering);
    }

    @Transactional
    public void cancel(UUID offeringId, String reason, UUID adminId) {
        Offering offering = findOffering(offeringId);
        if (!offering.isActive()) {
            throw new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", "Offering is already cancelled");
        }
        // S5 enforces the authoritative active-order-line check. S4 fast-paths on reservedCount.
        if (offering.getReservedCount() > 0) {
            throw new AuthException(HttpStatus.CONFLICT, "INVALID_STATE",
                "Offering has active order lines and cannot be cancelled");
        }
        offering.cancel(Instant.now());
        audit.record(ActorType.ADMIN, adminId, "OFFERING_CANCELLED", "OFFERING", offeringId,
            Map.of("status", "ACTIVE"), cancellationDetail("CANCELLED", reason));
    }

    private ItemTemplate activeTemplate(UUID templateId) {
        if (templateId == null) {
            throw new RequestValidationException("itemTemplateId", "is required");
        }
        return templates.findById(templateId)
            .filter(template -> template.isActive())
            .orElseThrow(() -> new RequestValidationException("itemTemplateId",
                "must reference an active item template"));
    }

    /**
     * Resolve snapshot fields. On create ({@code current == null}), omitted fields default from the
     * template. On update, omitted fields keep the stored offering value — the template is never
     * consulted, so a later template edit can never leak into an existing offering snapshot.
     */
    private Resolved resolve(MenuApi.OfferingWrite request, ItemTemplate template, Offering current) {
        boolean create = current == null;
        List<FieldError> errors = new ArrayList<>();
        String name = firstNonBlank(request.name(), create ? template.getName() : current.getName());
        String description = firstNonNull(request.description(),
            create ? template.getDescription() : current.getDescription());
        UUID photoMediaId = request.photoMediaId() != null ? request.photoMediaId()
            : (create ? template.getPhotoMediaId() : current.getPhotoMediaId());
        String ingredients = firstNonNull(request.ingredientsText(),
            create ? template.getIngredientsText() : current.getIngredientsText());
        List<String> allergens = request.allergenTags() != null ? request.allergenTags()
            : (create ? template.getAllergenTags() : current.getAllergenTags());
        int price = request.priceCents();
        int quantity = request.quantityLimit();
        Integer maxPerCustomer = request.maxPerCustomer();

        if (name == null || name.isBlank() || name.length() > 120) {
            errors.add(new FieldError("name", "must be 1-120 characters"));
        }
        if (price < 0 || price > 99999) errors.add(new FieldError("priceCents", "must be 0-99999"));
        if (quantity < 1 || quantity > 9999) errors.add(new FieldError("quantityLimit", "must be 1-9999"));
        if (maxPerCustomer != null && (maxPerCustomer < 1 || maxPerCustomer > quantity)) {
            errors.add(new FieldError("maxPerCustomer", "must be between 1 and quantityLimit"));
        }
        if (allergens != null) {
            if (allergens.size() > 20) errors.add(new FieldError("allergenTags", "must contain at most 20 tags"));
            if (allergens.stream().anyMatch(tag -> tag == null || tag.length() > 60)) {
                errors.add(new FieldError("allergenTags", "each tag must be at most 60 characters"));
            }
        }

        List<OfferingOptionGroup> groups;
        if (request.customizationGroups() == null) {
            groups = create ? snapshotTemplateGroups(template) : copyCurrentGroups(current);
        } else {
            groups = snapshotRequestGroups(request.customizationGroups(), errors);
        }

        if (!errors.isEmpty()) throw new RequestValidationException(errors);
        return new Resolved(name.trim(), description, photoMediaId, ingredients,
            allergens == null ? List.of() : List.copyOf(allergens), price, quantity, maxPerCustomer, groups);
    }

    private List<OfferingOptionGroup> copyCurrentGroups(Offering current) {
        List<OfferingOptionGroup> result = new ArrayList<>();
        for (OfferingOptionGroup group : current.getGroups()) {
            OfferingOptionGroup copy = new OfferingOptionGroup(Uuids.v7(), group.getSourceGroupId(), group.getName(),
                group.getSelectionType(), group.isRequired(), group.getMinSelections(), group.getMaxSelections(),
                group.getSortOrder());
            List<OfferingOption> options = new ArrayList<>();
            for (OfferingOption option : group.getOptions()) {
                options.add(new OfferingOption(Uuids.v7(), copy, option.getSourceOptionId(), option.getName(),
                    option.getPriceDeltaCents(), option.isAvailable(), option.getSortOrder()));
            }
            copy.replaceOptions(options);
            result.add(copy);
        }
        return result;
    }

    private List<OfferingOptionGroup> snapshotTemplateGroups(ItemTemplate template) {
        List<OfferingOptionGroup> result = new ArrayList<>();
        for (CustomizationGroup group : template.getGroups()) {
            OfferingOptionGroup copy = new OfferingOptionGroup(Uuids.v7(), group.getId(), group.getName(),
                group.getSelectionType(), group.isRequired(), group.getMinSelections(), group.getMaxSelections(),
                group.getSortOrder());
            List<OfferingOption> options = new ArrayList<>();
            int index = 0;
            for (CustomizationOption option : group.getOptions()) {
                options.add(new OfferingOption(Uuids.v7(), copy, option.getId(), option.getName(),
                    option.getPriceDeltaCents(), option.isAvailable(),
                    option.getSortOrder() == 0 ? index : option.getSortOrder()));
                index++;
            }
            copy.replaceOptions(options);
            result.add(copy);
        }
        return result;
    }

    private List<OfferingOptionGroup> snapshotRequestGroups(List<MenuApi.OfferingOptionGroupWrite> source,
            List<FieldError> errors) {
        List<OfferingOptionGroup> result = new ArrayList<>();
        if (source.size() > 20) errors.add(new FieldError("customizationGroups", "must contain at most 20 groups"));
        Set<String> groupNames = new HashSet<>();
        for (int g = 0; g < source.size(); g++) {
            MenuApi.OfferingOptionGroupWrite group = source.get(g);
            String field = "customizationGroups[" + g + "]";
            String name = group.name() == null ? "" : group.name().trim();
            if (name.isEmpty() || name.length() > 80) {
                errors.add(new FieldError(field + ".name", "must be 1-80 characters"));
            }
            if (!groupNames.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(new FieldError(field + ".name", "duplicate group name"));
            }
            if (group.selectionType() == null) errors.add(new FieldError(field + ".selectionType", "is required"));
            if (group.required() == null) errors.add(new FieldError(field + ".required", "is required"));
            int min = group.minSelections() == null ? -1 : group.minSelections();
            int max = group.maxSelections() == null ? 0 : group.maxSelections();
            if (min < 0 || max < 1 || min > max) {
                errors.add(new FieldError(field, "minSelections/maxSelections range is invalid"));
            }
            if (group.selectionType() == SelectionType.SINGLE && max != 1) {
                errors.add(new FieldError(field + ".maxSelections", "SINGLE requires maxSelections = 1"));
            }

            OfferingOptionGroup copy = new OfferingOptionGroup(Uuids.v7(), group.id(), name,
                group.selectionType(), Boolean.TRUE.equals(group.required()), min, max,
                group.sortOrder() == null ? g : group.sortOrder());
            List<MenuApi.OfferingOptionWrite> options = group.options();
            if (options == null || options.isEmpty() || options.size() > 50) {
                errors.add(new FieldError(field + ".options", "must contain 1-50 options"));
            }
            List<OfferingOption> copiedOptions = new ArrayList<>();
            Set<String> optionNames = new HashSet<>();
            int index = 0;
            List<MenuApi.OfferingOptionWrite> optionList = options == null
                ? List.<MenuApi.OfferingOptionWrite>of() : options;
            for (MenuApi.OfferingOptionWrite option : optionList) {
                String optionField = field + ".options[" + index + "]";
                String optionName = option.name() == null ? "" : option.name().trim();
                if (optionName.isEmpty() || optionName.length() > 80) {
                    errors.add(new FieldError(optionField + ".name", "must be 1-80 characters"));
                }
                if (!optionNames.add(optionName.toLowerCase(Locale.ROOT))) {
                    errors.add(new FieldError(optionField + ".name", "duplicate option name in group"));
                }
                int price = option.priceDeltaCents() == null ? -1 : option.priceDeltaCents();
                if (price < 0) errors.add(new FieldError(optionField + ".priceDeltaCents", "must be >= 0"));
                copiedOptions.add(new OfferingOption(Uuids.v7(), copy, option.id(), optionName, price,
                    option.available() == null || option.available(),
                    option.sortOrder() == null ? index : option.sortOrder()));
                index++;
            }
            copy.replaceOptions(copiedOptions);
            result.add(copy);
        }
        return result;
    }

    private List<PickupWindow> validateWindows(UUID dateId, List<UUID> windowIds, UUID currentOfferingId) {
        if (windowIds == null || windowIds.isEmpty()) {
            throw new RequestValidationException("windowIds", "at least one window is required");
        }
        List<PickupWindow> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID windowId : windowIds) {
            if (!seen.add(windowId)) {
                throw new RequestValidationException("windowIds", "duplicate window id");
            }
            PickupWindow window = windows.findById(windowId)
                .orElseThrow(() -> new RequestValidationException("windowIds", "unknown window " + windowId));
            if (!window.getMenuDateId().equals(dateId) || !window.isActive()) {
                throw new RequestValidationException("windowIds",
                    "window must be active and belong to the same bake date");
            }
            result.add(window);
        }
        return result;
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
            throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Active menu date not found");
        }
        return date;
    }

    private Offering findOffering(UUID offeringId) {
        return offerings.findById(offeringId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Offering not found"));
    }

    private static String firstNonBlank(String requested, String fallback) {
        return requested != null && !requested.isBlank() ? requested : fallback;
    }

    private static String firstNonNull(String requested, String fallback) {
        return requested != null ? requested : fallback;
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

    private record Resolved(String name, String description, UUID photoMediaId, String ingredientsText,
            List<String> allergenTags, int priceCents, int quantityLimit, Integer maxPerCustomer,
            List<OfferingOptionGroup> groups) {}
}
