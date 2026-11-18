package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.api.catalog.CatalogApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.common.error.Problem;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.catalog.CustomizationGroup;
import com.bethanysbakery.api.domain.catalog.CustomizationOption;
import com.bethanysbakery.api.domain.catalog.ItemTemplate;
import com.bethanysbakery.api.domain.catalog.ItemTemplateRepository;
import com.bethanysbakery.api.domain.catalog.Media;
import com.bethanysbakery.api.domain.catalog.MediaRepository;
import com.bethanysbakery.api.domain.catalog.MediaStatus;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemTemplateService {

    private final ItemTemplateRepository templates;
    private final MediaRepository mediaRepository;
    private final MediaReferenceService mediaReferences;
    private final AuditService audit;

    public ItemTemplateService(ItemTemplateRepository templates, MediaRepository mediaRepository,
            MediaReferenceService mediaReferences, AuditService audit) {
        this.templates = templates;
        this.mediaRepository = mediaRepository;
        this.mediaReferences = mediaReferences;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CatalogApi.ItemTemplate> list(Boolean active) {
        List<ItemTemplate> found = active == null
            ? templates.findAllByOrderByNameAsc()
            : templates.findAllByActiveOrderByNameAsc(active);
        return found.stream().map(this::toApi).toList();
    }

    @Transactional(readOnly = true)
    public CatalogApi.ItemTemplate get(UUID id) {
        return toApi(findTemplate(id));
    }

    @Transactional
    public CatalogApi.ItemTemplate create(CatalogApi.ItemTemplateWrite request, UUID adminId) {
        ValidatedTemplate input = validate(request);
        Instant now = Instant.now();
        ItemTemplate template = new ItemTemplate(Uuids.v7(), input.name(), input.description(), input.photoMediaId(),
            input.ingredientsText(), input.allergenTags(), input.active(), now);
        applyGroups(template, input.groups());
        templates.save(template);
        audit.record(ActorType.ADMIN, adminId, "ITEM_TEMPLATE_CREATED", "ITEM_TEMPLATE", template.getId(),
            null, summary(template));
        return toApi(template);
    }

    @Transactional
    public CatalogApi.ItemTemplate replace(UUID id, CatalogApi.ItemTemplateWrite request, UUID adminId) {
        ItemTemplate template = findTemplate(id);
        Map<String, Object> before = summary(template);
        ValidatedTemplate input = validate(request);
        template.update(input.name(), input.description(), input.photoMediaId(), input.ingredientsText(),
            input.allergenTags(), input.active(), Instant.now());
        applyGroups(template, input.groups());
        templates.save(template);
        audit.record(ActorType.ADMIN, adminId, "ITEM_TEMPLATE_REPLACED", "ITEM_TEMPLATE", id,
            before, summary(template));
        return toApi(template);
    }

    @Transactional
    public void deactivate(UUID id, UUID adminId) {
        ItemTemplate template = findTemplate(id);
        Map<String, Object> before = summary(template);
        template.deactivate(Instant.now());
        templates.save(template);
        audit.record(ActorType.ADMIN, adminId, "ITEM_TEMPLATE_DEACTIVATED", "ITEM_TEMPLATE", id,
            before, summary(template));
    }

    private ItemTemplate findTemplate(UUID id) {
        return templates.findById(id)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Item template not found"));
    }

    private ValidatedTemplate validate(CatalogApi.ItemTemplateWrite request) {
        List<Problem.FieldError> errors = new ArrayList<>();
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 120) errors.add(error("name", "must be 1-120 characters"));

        String description = blankToNull(request.description());
        if (description != null && description.length() > 2000) {
            errors.add(error("description", "must be at most 2000 characters"));
        }
        String ingredients = blankToNull(request.ingredientsText());
        if (ingredients != null && ingredients.length() > 4000) {
            errors.add(error("ingredientsText", "must be at most 4000 characters"));
        }

        if (request.allergenTags() != null) {
            for (String tag : request.allergenTags()) {
                if (tag != null && tag.trim().length() > 60) {
                    errors.add(error("allergenTags", "each tag must be at most 60 characters"));
                    break;
                }
            }
        }
        List<String> allergens = deduplicate(request.allergenTags());
        if (allergens.size() > 20) errors.add(error("allergenTags", "must contain at most 20 tags"));

        if (request.photoMediaId() != null && !mediaReferences.lockAndCheckActive(request.photoMediaId())) {
            errors.add(error("photoMediaId", "must reference active media"));
        }
        if (request.active() == null) errors.add(error("active", "is required"));

        ValidatedTemplate result = new ValidatedTemplate(name, description, request.photoMediaId(), ingredients,
            allergens, Boolean.TRUE.equals(request.active()), validateGroups(request.customizationGroups(), errors));

        if (!errors.isEmpty()) throw new RequestValidationException(errors);
        return result;
    }

    private List<ValidatedGroup> validateGroups(List<CatalogApi.CustomizationGroupWrite> source,
            List<Problem.FieldError> errors) {
        if (source == null) return List.of();
        if (source.size() > 20) errors.add(error("customizationGroups", "must contain at most 20 groups"));
        List<ValidatedGroup> result = new ArrayList<>();
        Set<UUID> seenGroupIds = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            CatalogApi.CustomizationGroupWrite group = source.get(i);
            String field = "customizationGroups[" + i + "]";
            if (group == null) {
                errors.add(error(field, "must not be null"));
                continue;
            }
            if (group.id() != null && !seenGroupIds.add(group.id())) {
                errors.add(error(field + ".id", "duplicate group id"));
            }
            String name = group.name() == null ? "" : group.name().trim();
            if (name.isEmpty() || name.length() > 80) errors.add(error(field + ".name", "must be 1-80 characters"));
            if (group.selectionType() == null) errors.add(error(field + ".selectionType", "is required"));
            if (group.required() == null) errors.add(error(field + ".required", "is required"));

            int min = group.minSelections() == null ? -1 : group.minSelections();
            int max = group.maxSelections() == null ? 0 : group.maxSelections();
            if (min < 0) errors.add(error(field + ".minSelections", "must be >= 0"));
            if (max < 1) errors.add(error(field + ".maxSelections", "must be >= 1"));
            if (min >= 0 && max >= 1 && min > max) {
                errors.add(error(field + ".minSelections", "must be <= maxSelections"));
            }
            if (group.selectionType() == SelectionType.SINGLE) {
                if (max != 1) errors.add(error(field + ".maxSelections", "SINGLE requires maxSelections = 1"));
                if (Boolean.TRUE.equals(group.required()) && min != 1) {
                    errors.add(error(field + ".minSelections", "required SINGLE requires minSelections = 1"));
                }
                if (Boolean.FALSE.equals(group.required()) && min != 0) {
                    errors.add(error(field + ".minSelections", "optional SINGLE requires minSelections = 0"));
                }
            }
            if (group.options() == null || group.options().isEmpty()) {
                errors.add(error(field + ".options", "must contain at least one option"));
            }
            result.add(new ValidatedGroup(group.id(), name, group.selectionType(),
                Boolean.TRUE.equals(group.required()), min, max,
                group.sortOrder() == null ? 0 : group.sortOrder(),
                validateOptions(group.options(), field, errors)));
        }
        return result;
    }

    private List<ValidatedOption> validateOptions(List<CatalogApi.CustomizationOptionWrite> source, String groupField,
            List<Problem.FieldError> errors) {
        if (source == null) return List.of();
        if (source.size() > 50) errors.add(error(groupField + ".options", "must contain at most 50 options"));
        List<ValidatedOption> result = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            CatalogApi.CustomizationOptionWrite option = source.get(i);
            String field = groupField + ".options[" + i + "]";
            if (option == null) {
                errors.add(error(field, "must not be null"));
                continue;
            }
            if (option.id() != null && !seenIds.add(option.id())) {
                errors.add(error(field + ".id", "duplicate option id"));
            }
            String name = option.name() == null ? "" : option.name().trim();
            if (name.isEmpty() || name.length() > 80) errors.add(error(field + ".name", "must be 1-80 characters"));
            if (!seenNames.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(error(field + ".name", "duplicate option name in group"));
            }
            int price = option.priceDeltaCents() == null ? -1 : option.priceDeltaCents();
            if (price < 0) errors.add(error(field + ".priceDeltaCents", "must be >= 0"));
            result.add(new ValidatedOption(option.id(), name, price,
                option.available() == null || option.available(),
                option.sortOrder() == null ? 0 : option.sortOrder()));
        }
        return result;
    }

    private void applyGroups(ItemTemplate template, List<ValidatedGroup> groups) {
        List<Problem.FieldError> errors = new ArrayList<>();
        Map<UUID, CustomizationGroup> existingGroups = new HashMap<>();
        template.getGroups().forEach(group -> existingGroups.put(group.getId(), group));

        List<CustomizationGroup> desired = new ArrayList<>();
        for (ValidatedGroup input : groups) {
            CustomizationGroup group = resolveGroup(input, existingGroups, errors);
            if (group == null) continue;
            applyOptions(group, input.options(), errors);
            desired.add(group);
        }
        if (!errors.isEmpty()) throw new RequestValidationException(errors);
        template.replaceGroups(desired);
    }

    /** Returns a reused or newly created group, or {@code null} when the supplied id is unknown. */
    private CustomizationGroup resolveGroup(ValidatedGroup input, Map<UUID, CustomizationGroup> existingGroups,
            List<Problem.FieldError> errors) {
        if (input.id() == null) {
            return new CustomizationGroup(Uuids.v7(), input.name(), input.selectionType(), input.required(),
                input.minSelections(), input.maxSelections(), input.sortOrder());
        }
        CustomizationGroup group = existingGroups.remove(input.id());
        if (group == null) {
            errors.add(error("customizationGroups", "unknown nested group id " + input.id()));
            return null;
        }
        group.update(input.name(), input.selectionType(), input.required(), input.minSelections(),
            input.maxSelections(), input.sortOrder());
        return group;
    }

    private void applyOptions(CustomizationGroup group, List<ValidatedOption> inputs,
            List<Problem.FieldError> errors) {
        Map<UUID, CustomizationOption> existingOptions = new HashMap<>();
        group.getOptions().forEach(option -> existingOptions.put(option.getId(), option));
        List<CustomizationOption> desired = new ArrayList<>();
        for (ValidatedOption input : inputs) {
            CustomizationOption option = resolveOption(input, group, existingOptions, errors);
            if (option != null) desired.add(option);
        }
        group.replaceOptions(desired);
    }

    /** Returns a reused or newly created option, or {@code null} when the supplied id is unknown. */
    private CustomizationOption resolveOption(ValidatedOption input, CustomizationGroup group,
            Map<UUID, CustomizationOption> existingOptions, List<Problem.FieldError> errors) {
        if (input.id() == null) {
            return new CustomizationOption(Uuids.v7(), group, input.name(), input.priceDeltaCents(),
                input.available(), input.sortOrder());
        }
        CustomizationOption option = existingOptions.remove(input.id());
        if (option == null) {
            errors.add(error("customizationGroups", "unknown nested option id " + input.id()));
            return null;
        }
        option.update(input.name(), input.priceDeltaCents(), input.available(), input.sortOrder());
        return option;
    }

    private CatalogApi.ItemTemplate toApi(ItemTemplate template) {
        CatalogApi.MediaView photo = null;
        if (template.getPhotoMediaId() != null) {
            photo = mediaRepository.findById(template.getPhotoMediaId())
                .filter(media -> media.getStatus() == MediaStatus.ACTIVE)
                .map(this::toMediaView)
                .orElse(null);
        }
        List<CatalogApi.CustomizationGroup> groups = template.getGroups().stream()
            .sorted(Comparator.comparingInt(CustomizationGroup::getSortOrder)
                .thenComparing(group -> group.getId().toString()))
            .map(this::toApi)
            .toList();
        return new CatalogApi.ItemTemplate(template.getId(), template.getName(), template.getDescription(), photo,
            template.getIngredientsText(), List.copyOf(template.getAllergenTags()), template.isActive(), groups,
            template.getVersion(), template.getCreatedAt(), template.getUpdatedAt());
    }

    private CatalogApi.CustomizationGroup toApi(CustomizationGroup group) {
        List<CatalogApi.CustomizationOption> options = group.getOptions().stream()
            .sorted(Comparator.comparingInt(CustomizationOption::getSortOrder)
                .thenComparing(option -> option.getId().toString()))
            .map(option -> new CatalogApi.CustomizationOption(option.getId(), option.getName(),
                option.getPriceDeltaCents(), option.isAvailable(), option.getSortOrder()))
            .toList();
        return new CatalogApi.CustomizationGroup(group.getId(), group.getName(), group.getSelectionType(),
            group.isRequired(), group.getMinSelections(), group.getMaxSelections(), group.getSortOrder(), options);
    }

    private CatalogApi.MediaView toMediaView(Media media) {
        return MediaViews.of(media);
    }

    private Map<String, Object> summary(ItemTemplate template) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", template.getId());
        result.put("name", template.getName());
        result.put("active", template.isActive());
        result.put("photoMediaId", template.getPhotoMediaId());
        result.put("groupCount", template.getGroups().size());
        return result;
    }

    private List<String> deduplicate(List<String> tags) {
        if (tags == null) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : tags) {
            if (raw == null) continue;
            String tag = raw.trim();
            if (tag.isEmpty()) continue;
            unique.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
        }
        return new ArrayList<>(unique.values());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Problem.FieldError error(String field, String message) {
        return new Problem.FieldError(field, message);
    }

    private record ValidatedTemplate(String name, String description, UUID photoMediaId, String ingredientsText,
            List<String> allergenTags, boolean active, List<ValidatedGroup> groups) {}

    private record ValidatedGroup(UUID id, String name, SelectionType selectionType, boolean required,
            int minSelections, int maxSelections, int sortOrder, List<ValidatedOption> options) {}

    private record ValidatedOption(UUID id, String name, int priceDeltaCents, boolean available, int sortOrder) {}
}
