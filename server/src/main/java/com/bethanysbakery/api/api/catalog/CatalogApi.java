package com.bethanysbakery.api.api.catalog;

import com.bethanysbakery.api.domain.catalog.SelectionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogApi {

    private CatalogApi() {}

    public record MediaView(UUID id, String url, int width, int height, long sizeBytes,
            Map<String, String> variants) {}

    public record ItemTemplate(
        UUID id,
        String name,
        String description,
        MediaView photo,
        String ingredientsText,
        List<String> allergenTags,
        boolean active,
        List<CustomizationGroup> customizationGroups,
        int version,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ItemTemplateWrite(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        UUID photoMediaId,
        @Size(max = 4000) String ingredientsText,
        @Size(max = 20) List<@NotBlank @Size(max = 60) String> allergenTags,
        @NotNull Boolean active,
        @Valid @Size(max = 20) List<@Valid CustomizationGroupWrite> customizationGroups
    ) {}

    public record CustomizationGroup(
        UUID id,
        String name,
        SelectionType selectionType,
        boolean required,
        int minSelections,
        int maxSelections,
        int sortOrder,
        List<CustomizationOption> options
    ) {}

    public record CustomizationGroupWrite(
        UUID id,
        @NotBlank @Size(max = 80) String name,
        @NotNull SelectionType selectionType,
        @NotNull Boolean required,
        @NotNull @Min(0) Integer minSelections,
        @NotNull @Min(1) Integer maxSelections,
        @Min(Integer.MIN_VALUE) @Max(Integer.MAX_VALUE) Integer sortOrder,
        @Valid @Size(min = 1, max = 50) List<@Valid CustomizationOptionWrite> options
    ) {}

    public record CustomizationOption(
        UUID id,
        String name,
        int priceDeltaCents,
        boolean available,
        int sortOrder
    ) {}

    public record CustomizationOptionWrite(
        UUID id,
        @NotBlank @Size(max = 80) String name,
        @NotNull @Min(0) Integer priceDeltaCents,
        Boolean available,
        Integer sortOrder
    ) {}
}
