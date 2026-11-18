package com.bethanysbakery.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bethanysbakery.api.api.catalog.CatalogApi;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.common.error.Problem;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.catalog.ItemTemplate;
import com.bethanysbakery.api.domain.catalog.ItemTemplateRepository;
import com.bethanysbakery.api.domain.catalog.MediaRepository;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItemTemplateServiceTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ItemTemplateRepository templates;

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private MediaReferenceService mediaReferences;

    @Mock
    private AuditService audit;

    @InjectMocks
    private ItemTemplateService service;

    @Test
    void rejectsSingleGroupWithMaxSelectionsAboveOne() {
        RequestValidationException failure = rejection(template(group("Size", SelectionType.SINGLE, true,
            1, 2, option("Large", 0, 0))));

        assertThat(failure.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].maxSelections");
    }

    @Test
    void rejectsMultiGroupWithMinAboveMax() {
        RequestValidationException failure = rejection(template(group("Toppings", SelectionType.MULTI, false,
            3, 2, option("A", 0, 0))));

        assertThat(failure.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].minSelections");
    }

    @Test
    void rejectsDuplicateOptionNamesWithinOneGroup() {
        RequestValidationException failure = rejection(template(group("Toppings", SelectionType.MULTI, false,
            0, 2, option("Sprinkles", 0, 0), option(" sprinkles ", 10, 1))));

        assertThat(failure.errors()).extracting(Problem.FieldError::message)
            .contains("duplicate option name in group");
    }

    @Test
    void acceptsValidTemplateSortsOptionsAndAuditsCreation() {
        CatalogApi.ItemTemplate created = service.create(template(group("Toppings", SelectionType.MULTI, false,
            0, 2, option("B", 20, 2), option("A", 10, 1))), ADMIN);

        assertThat(created.name()).isEqualTo("Test");
        assertThat(created.customizationGroups().get(0).options())
            .extracting(CatalogApi.CustomizationOption::name)
            .containsExactly("A", "B");
        verify(templates).save(any(ItemTemplate.class));
        verify(audit).record(eq(ActorType.ADMIN), eq(ADMIN), eq("ITEM_TEMPLATE_CREATED"),
            eq("ITEM_TEMPLATE"), any(UUID.class), isNull(), anyMap());
    }

    @Test
    void deduplicatesAllergenTagsCaseInsensitively() {
        CatalogApi.ItemTemplateWrite request = new CatalogApi.ItemTemplateWrite("Test", null, null, null,
            List.of("Gluten", "gluten", "DAIRY"), true, List.of());

        assertThat(service.create(request, ADMIN).allergenTags()).containsExactly("Gluten", "DAIRY");
    }

    @Test
    void rejectsPhotoReferencingInactiveMedia() {
        UUID photoId = UUID.randomUUID();
        CatalogApi.ItemTemplateWrite request = new CatalogApi.ItemTemplateWrite(
            "Test", null, photoId, null, List.of(), true, List.of());
        when(mediaReferences.lockAndCheckActive(photoId)).thenReturn(false);

        RequestValidationException failure = rejection(request);

        assertThat(failure.errors()).extracting(Problem.FieldError::field).contains("photoMediaId");
        verify(templates, never()).save(any());
    }

    @Test
    void rejectsOutOfRangeTextAndAllergenFields() {
        List<String> tooManyAllergens = IntStream.range(0, 21)
            .mapToObj(i -> "allergen" + i).toList();
        CatalogApi.ItemTemplateWrite request = new CatalogApi.ItemTemplateWrite(
            "x".repeat(121), "d".repeat(2001), null, "i".repeat(4001),
            tooManyAllergens, true, List.of());

        RequestValidationException failure = rejection(request);

        assertThat(failure.errors()).extracting(Problem.FieldError::field)
            .contains("name", "description", "ingredientsText", "allergenTags");
        verify(templates, never()).save(any());
    }

    @Test
    void rejectsAllergenTagLongerThanSixtyCharacters() {
        CatalogApi.ItemTemplateWrite request = new CatalogApi.ItemTemplateWrite("Test", null, null, null,
            List.of("x".repeat(61)), true, List.of());

        RequestValidationException failure = rejection(request);

        assertThat(failure.errors()).extracting(Problem.FieldError::field).contains("allergenTags");
        verify(templates, never()).save(any());
    }

    @Test
    void preservesUnavailableOptionFlag() {
        CatalogApi.CustomizationGroupWrite group = new CatalogApi.CustomizationGroupWrite(null, "Toppings",
            SelectionType.MULTI, false, 0, 2, 0,
            List.of(new CatalogApi.CustomizationOptionWrite(null, "Sold out", 0, false, 0)));

        CatalogApi.ItemTemplate created = service.create(template(group), ADMIN);

        assertThat(created.customizationGroups().get(0).options().get(0).available()).isFalse();
    }

    @Test
    void rejectsSingleGroupWithInconsistentRequiredFlag() {
        RequestValidationException required = rejection(template(group("Size", SelectionType.SINGLE, true,
            0, 1, option("Large", 0, 0))));
        assertThat(required.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].minSelections");

        RequestValidationException optional = rejection(template(group("Size", SelectionType.SINGLE, false,
            1, 1, option("Large", 0, 0))));
        assertThat(optional.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].minSelections");
    }

    @Test
    void rejectsNegativePriceDelta() {
        RequestValidationException failure = rejection(template(group("Toppings", SelectionType.MULTI, false,
            0, 2, option("A", -1, 0))));

        assertThat(failure.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].options[0].priceDeltaCents");
    }

    @Test
    void rejectsGroupWithoutOptions() {
        RequestValidationException failure = rejection(template(group("Empty", SelectionType.MULTI, false, 0, 2)));

        assertThat(failure.errors()).extracting(Problem.FieldError::field)
            .contains("customizationGroups[0].options");
    }

    private RequestValidationException rejection(CatalogApi.ItemTemplateWrite request) {
        return (RequestValidationException) catchThrowable(() -> service.create(request, ADMIN));
    }

    private CatalogApi.ItemTemplateWrite template(CatalogApi.CustomizationGroupWrite... groups) {
        return new CatalogApi.ItemTemplateWrite("Test", null, null, null, List.of(), true, List.of(groups));
    }

    private CatalogApi.CustomizationGroupWrite group(String name, SelectionType type, boolean required,
            int min, int max, CatalogApi.CustomizationOptionWrite... options) {
        return new CatalogApi.CustomizationGroupWrite(null, name, type, required, min, max, 0, List.of(options));
    }

    private CatalogApi.CustomizationOptionWrite option(String name, int price, int sortOrder) {
        return new CatalogApi.CustomizationOptionWrite(null, name, price, true, sortOrder);
    }
}
