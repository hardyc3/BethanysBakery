package com.bethanysbakery.api.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.support.CatalogTestImages;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** S4 menu/date/window/offering lifecycle, publish validation, and public reads. */
class MenuIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";
    private static final ZoneId ZONE = ZoneId.of("America/Denver");

    private String accessToken;
    private String deviceKey;

    @BeforeEach
    void authenticateAdmin() throws Exception {
        var admin = seedAdmin("menus@example.com", PASSWORD, List.of());
        var device = seedDevice(admin.id(), "menus-device-key", "Pixel");
        deviceKey = device.apiKey();
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"menus@example.com","password":"%s","totpCode":"%s"}
                    """.formatted(PASSWORD, currentTotpCode(admin.totpSecret()))))
            .andExpect(status().isOk())
            .andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    @Test
    void publishHappyPathMakesDataPublicWithNoStoreAndAbsoluteUrls() throws Exception {
        String mediaId = uploadMedia();
        String templateId = createTemplate("Cinnamon Roll", "Toppings", mediaId);
        LocalDate bake = futureBakeDate(30);
        String menuId = createMenu("Weekly Menu");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 50);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 550, 20, null, null);

        MvcResult published = mvc.perform(post("/admin/v1/menus/" + menuId + "/publish")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationEnabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.publishedAt").exists())
            .andExpect(jsonPath("$.notifyAt").value(
                bake.minusDays(4).atTime(9, 0).atZone(ZONE).toInstant().toString()))
            .andExpect(jsonPath("$.dates[0].status").value("PUBLISHED"))
            .andReturn();
        assertThat(published.getResponse().getContentAsString()).contains(offeringId);

        mvc.perform(get("/api/v1/menu-dates"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].windowCount").value(1))
            .andExpect(jsonPath("$[0].offeringCount").value(1));

        mvc.perform(get("/api/v1/menu-dates/" + dateId))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.pickupWindows.length()").value(1))
            .andExpect(jsonPath("$.offerings.length()").value(1))
            .andExpect(jsonPath("$.offerings[0].soldOut").value(false))
            .andExpect(jsonPath("$.offerings[0].customizationGroups[0].name").value("Toppings"))
            .andExpect(jsonPath("$.offerings[0].photoUrl").value(
                "https://public.example.com/api/v1/media/" + mediaId))
            .andExpect(jsonPath("$.offerings[0].photoVariants.400").value(
                "https://public.example.com/api/v1/media/" + mediaId + "?size=400"));

        mvc.perform(get("/api/v1/offerings/" + offeringId))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.id").value(offeringId));

        assertThat(countAudit("MENU_PUBLISHED")).isEqualTo(1);
    }

    @Test
    void publishValidationRejectsEmptyDraftAndIncompleteDates() throws Exception {
        String menuId = createMenu("Empty");
        publish(menuId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PUBLISH_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String dateId = addDate(menuId, bake);
        publish(menuId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PUBLISH_VALIDATION_FAILED"));

        createWindow(dateId, bake, 12, 14, 10);
        publish(menuId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PUBLISH_VALIDATION_FAILED"));
    }

    @Test
    void publishValidationRejectsUnassignedOffering() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Assignment");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        jdbc.update("delete from offering_window where offering_id = ?::uuid", offeringId);

        publish(menuId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PUBLISH_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field").value(
                org.hamcrest.Matchers.hasItem(containsString("windowIds"))));
    }

    @Test
    void pastNotificationOverrideIsClampedToNow() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(30);
        String menuId = createMenu("Past override");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        Instant before = Instant.now();
        JsonNode body = objectMapper.readTree(mvc.perform(post("/admin/v1/menus/" + menuId + "/publish")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationEnabled\":true,\"notifyAt\":\"2020-01-01T00:00:00Z\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        Instant notifyAt = Instant.parse(body.get("notifyAt").asText());
        assertThat(notifyAt).isAfterOrEqualTo(before).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void addingDateToPublishedMenuIsImmediatelyVisibleWithoutNewNotification() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(30);
        String menuId = createMenu("Growing");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());

        LocalDate extra = bake.plusDays(1);
        JsonNode added = objectMapper.readTree(mvc.perform(post("/admin/v1/menus/" + menuId + "/dates")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + extra + "\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(added.get("status").asText()).isEqualTo("PUBLISHED");

        mvc.perform(get("/api/v1/menu-dates").param("from", extra.toString()).param("to", extra.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        assertThat(countAudit("MENU_PUBLISHED")).isEqualTo(1);
    }

    @Test
    void offeringSnapshotIsImmutableWhenTemplateChanges() throws Exception {
        String templateId = createTemplate("Original name", "Original group", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Snapshot");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        mvc.perform(put("/admin/v1/item-templates/" + templateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateBody("Renamed template", "Renamed group", null)))
            .andExpect(status().isOk());

        mvc.perform(get("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Original name"))
            .andExpect(jsonPath("$.customizationGroups[0].name").value("Original group"));
    }

    @Test
    void offeringUpdateWithNullSnapshotFieldsNeverPullsFromEditedTemplate() throws Exception {
        // Snapshot with no description and no groups: a later template edit adds both. An offering
        // update that omits snapshot fields must keep the stored values, not inherit the template.
        ObjectNode slimTemplate = objectMapper.createObjectNode();
        slimTemplate.put("name", "Plain Roll");
        slimTemplate.put("active", true);
        String templateId = objectMapper.readTree(mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON).content(json(slimTemplate)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("id").asText();

        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Snapshot update");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        ObjectNode editedTemplate = (ObjectNode) objectMapper.readTree(templateBody("Renamed", "New group", null));
        editedTemplate.put("description", "New description from template");
        mvc.perform(put("/admin/v1/item-templates/" + templateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content(json(editedTemplate)))
            .andExpect(status().isOk());

        // Update only the price; every snapshot field is null in the request.
        mvc.perform(put("/admin/v1/offerings/" + offeringId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 600, 10, null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceCents").value(600));

        JsonNode after = objectMapper.readTree(mvc.perform(get("/admin/v1/offerings/" + offeringId)
                .headers(adminHeaders())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(after.get("name").asText()).isEqualTo("Plain Roll");
        assertThat(after.get("description").isNull()).isTrue();
        assertThat(after.get("customizationGroups").size()).isZero();
    }

    @Test
    void maxPerCustomerAboveQuantityIsRejected() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Limits");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 500, 5, 6, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].field").value("maxPerCustomer"));
    }

    @Test
    void loweringQuantityBelowReservedKeepsAvailabilityZeroAndUpdateSucceeds() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Reserved");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        jdbc.update("update offering set reserved_count = 10 where id = ?::uuid", offeringId);
        mvc.perform(put("/admin/v1/offerings/" + offeringId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 500, 5, null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reservedCount").value(10))
            .andExpect(jsonPath("$.availableQuantity").value(0));

        publish(menuId).andExpect(status().isOk());
        mvc.perform(get("/api/v1/offerings/" + offeringId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.soldOut").value(true))
            .andExpect(jsonPath("$.availableQuantity").value(0));
    }

    @Test
    void windowWithOfferingAssignmentCannotBeDeletedAndCancelHidesDate() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Cancel flow");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());

        mvc.perform(delete("/admin/v1/pickup-windows/" + windowId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WINDOW_IN_USE"));

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sold out\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(get("/api/v1/menu-dates/" + dateId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/offerings/" + offeringId)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from pickup_window where id = ?::uuid", String.class, windowId))
            .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from offering where id = ?::uuid", String.class, offeringId))
            .isEqualTo("CANCELLED");
        assertThat(countAudit("MENU_DATE_CANCELLED")).isEqualTo(1);
    }

    @Test
    void offeringPhotoReferenceBlocksMediaDeletion() throws Exception {
        String mediaId = uploadMedia();
        String templateId = createTemplate("Roll", "X", mediaId);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Media ref");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);

        mvc.perform(delete("/admin/v1/media/" + mediaId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MEDIA_IN_USE"));
    }

    @Test
    void publishedMenuCannotBeDeletedAndCancelledMenuIsReadOnly() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Guards");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());

        mvc.perform(delete("/admin/v1/menus/" + menuId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        mvc.perform(post("/admin/v1/menus/" + menuId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/v1/menu-dates").param("from", bake.toString()).param("to", bake.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        assertThat(countAudit("MENU_CANCELLED")).isEqualTo(1);
    }

    @Test
    void windowWithReservedOrdersCannotBeDeletedEvenWithoutOfferings() throws Exception {
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Reserved window");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);

        // reserved_orders is owned by S5; set it directly to exercise the S4 WINDOW_IN_USE branch
        // that has no offering assignment.
        jdbc.update("update pickup_window set reserved_orders = 3 where id = ?::uuid", windowId);

        mvc.perform(delete("/admin/v1/pickup-windows/" + windowId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WINDOW_IN_USE"));

        mvc.perform(get("/admin/v1/menu-dates/" + dateId + "/pickup-windows").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].reservedOrders").value(3))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void loweringWindowCapacityBelowReservedOrdersKeepsZeroCapacityPublicly() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Window capacity");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        jdbc.update("update pickup_window set reserved_orders = 3 where id = ?::uuid", windowId);

        Instant start = bake.atTime(12, 0).atZone(ZONE).toInstant();
        Instant end = bake.atTime(14, 0).atZone(ZONE).toInstant();
        mvc.perform(put("/admin/v1/pickup-windows/" + windowId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Morning\",\"startAt\":\"" + start
                    + "\",\"endAt\":\"" + end + "\",\"maxOrders\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxOrders").value(2))
            .andExpect(jsonPath("$.reservedOrders").value(3))
            .andExpect(jsonPath("$.availableCapacity").value(0));

        publish(menuId).andExpect(status().isOk());
        mvc.perform(get("/api/v1/menu-dates/" + dateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pickupWindows[0].availableCapacity").value(0));
    }

    @Test
    void offeringWithReservationsCannotBeCancelled() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Reserved offering");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        jdbc.update("update offering set reserved_count = 2 where id = ?::uuid", offeringId);

        mvc.perform(delete("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        mvc.perform(get("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.reservedCount").value(2));
        assertThat(countAudit("OFFERING_CANCELLED")).isZero();
    }

    @Test
    void cancelOfferingWithNoReservationsHidesItFromPublicReads() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Cancel offering");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());
        mvc.perform(get("/api/v1/offerings/" + offeringId)).andExpect(status().isOk());

        mvc.perform(delete("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/v1/offerings/" + offeringId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/menu-dates/" + dateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offerings.length()").value(0));
        assertThat(countAudit("OFFERING_CANCELLED")).isEqualTo(1);
    }

    @Test
    void cancelDateWithoutReasonSucceeds() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Cancel no reason");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());

        // reason is optional per the spec; omitting it must still cancel the date.
        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(get("/api/v1/menu-dates/" + dateId)).andExpect(status().isNotFound());
    }

    @Test
    void draftMenuAndDateCanBeUpdatedAndDeleted() throws Exception {
        String menuId = createMenu("Original");
        mvc.perform(put("/admin/v1/menus/" + menuId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\",\"notes\":\"New notes\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Renamed"))
            .andExpect(jsonPath("$.notes").value("New notes"));

        LocalDate bake = futureBakeDate(20);
        String dateId = addDate(menuId, bake);
        LocalDate moved = bake.plusDays(1);
        mvc.perform(put("/admin/v1/menu-dates/" + dateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + moved + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bakeDate").value(moved.toString()));

        mvc.perform(delete("/admin/v1/menu-dates/" + dateId).headers(adminHeaders()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/admin/v1/menu-dates/" + dateId).headers(adminHeaders()))
            .andExpect(status().isNotFound());

        mvc.perform(delete("/admin/v1/menus/" + menuId).headers(adminHeaders()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/admin/v1/menus/" + menuId).headers(adminHeaders()))
            .andExpect(status().isNotFound());

        assertThat(countAudit("MENU_UPDATED")).isEqualTo(1);
        assertThat(countAudit("MENU_DATE_UPDATED")).isEqualTo(1);
        assertThat(countAudit("MENU_DATE_DELETED")).isEqualTo(1);
        assertThat(countAudit("MENU_DELETED")).isEqualTo(1);
    }

    @Test
    void publishedMenuTitleEditableButPublishedDateBakeDateIsLocked() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Published edit");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        createOffering(dateId, templateId, List.of(windowId), 500, 10, null, null);
        publish(menuId).andExpect(status().isOk());

        mvc.perform(put("/admin/v1/menus/" + menuId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed published\",\"notes\":\"still editable\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Renamed published"));

        mvc.perform(put("/admin/v1/menu-dates/" + dateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + bake.plusDays(1) + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void listAndGetOfferingsReturnSnapshotFields() throws Exception {
        String alphaTemplate = createTemplate("Alpha Roll", "X", null);
        String betaTemplate = createTemplate("Beta Loaf", "Y", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Offering reads");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String alphaId = createOffering(dateId, alphaTemplate, List.of(windowId), 550, 8, null, null);
        createOffering(dateId, betaTemplate, List.of(windowId), 700, 4, null, null);

        mvc.perform(get("/admin/v1/menu-dates/" + dateId + "/offerings").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Alpha Roll"))
            .andExpect(jsonPath("$[1].name").value("Beta Loaf"));

        mvc.perform(get("/admin/v1/offerings/" + alphaId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(alphaId))
            .andExpect(jsonPath("$.priceCents").value(550))
            .andExpect(jsonPath("$.quantityLimit").value(8))
            .andExpect(jsonPath("$.availableQuantity").value(8))
            .andExpect(jsonPath("$.windowIds[0]").value(windowId))
            .andExpect(jsonPath("$.customizationGroups[0].name").value("X"));
    }

    @Test
    void offeringWindowAssignmentMustBeActiveAndOnSameDate() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Window assignment");
        String dateId = addDate(menuId, bake);
        String otherDateId = addDate(menuId, bake.plusDays(1));
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String otherWindowId = createWindow(otherDateId, bake.plusDays(1), 12, 14, 10);

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(otherWindowId), 500, 10, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].field").value("windowIds"));

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId, windowId), 500, 10, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].field").value("windowIds"));
    }

    @Test
    void offeringPriceAndQuantityBoundariesAreEnforced() throws Exception {
        String templateId = createTemplate("Roll", "X", null);
        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Boundaries");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);

        // Inclusive lower price and upper quantity boundaries are accepted.
        createOffering(dateId, templateId, List.of(windowId), 0, 9999, null, null);

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 100000, 10, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 500, 0, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, List.of(windowId), 500, 10000, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void creatingOfferingSnapshotsAllTemplateFields() throws Exception {
        String mediaId = uploadMedia();
        ObjectNode template = objectMapper.createObjectNode();
        template.put("name", "Snapshotted Roll");
        template.put("description", "Template description");
        template.put("ingredientsText", "Flour, water");
        template.put("photoMediaId", mediaId);
        template.put("active", true);
        ArrayNode allergens = template.putArray("allergenTags");
        allergens.add("Gluten");
        allergens.add("Dairy");
        ArrayNode groups = template.putArray("customizationGroups");
        ObjectNode group = groups.addObject();
        group.put("name", "Toppings");
        group.put("selectionType", "SINGLE");
        group.put("required", true);
        group.put("minSelections", 1);
        group.put("maxSelections", 1);
        ArrayNode options = group.putArray("options");
        ObjectNode option = options.addObject();
        option.put("name", "Icing");
        option.put("priceDeltaCents", 50);
        String templateId = objectMapper.readTree(mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON).content(json(template)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("id").asText();

        LocalDate bake = futureBakeDate(20);
        String menuId = createMenu("Full snapshot");
        String dateId = addDate(menuId, bake);
        String windowId = createWindow(dateId, bake, 12, 14, 10);
        String offeringId = createOffering(dateId, templateId, List.of(windowId), 550, 20, null, null);

        mvc.perform(get("/admin/v1/offerings/" + offeringId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Snapshotted Roll"))
            .andExpect(jsonPath("$.description").value("Template description"))
            .andExpect(jsonPath("$.ingredientsText").value("Flour, water"))
            .andExpect(jsonPath("$.allergenTags[0]").value("Gluten"))
            .andExpect(jsonPath("$.allergenTags[1]").value("Dairy"))
            .andExpect(jsonPath("$.photo.id").value(mediaId))
            .andExpect(jsonPath("$.customizationGroups[0].name").value("Toppings"))
            .andExpect(jsonPath("$.customizationGroups[0].options[0].name").value("Icing"));
    }

    @Test
    void overlongCancelReasonIsRejectedBeforeHittingTheDatabase() throws Exception {
        String menuId = createMenu("Reason validation");
        String tooLong = "x".repeat(501);
        String body = objectMapper.writeValueAsString(java.util.Map.of("reason", tooLong));

        mvc.perform(post("/admin/v1/menus/" + menuId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/admin/v1/menus/" + menuId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));

        String dateId = addDate(menuId, futureBakeDate(20));
        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void movingBakeDateWithExistingWindowsIsRejected() throws Exception {
        String menuId = createMenu("Move date");
        LocalDate bake = futureBakeDate(20);
        String dateId = addDate(menuId, bake);
        createWindow(dateId, bake, 12, 14, 10);

        mvc.perform(put("/admin/v1/menu-dates/" + dateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + bake.plusDays(1) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].field").value("bakeDate"));

        mvc.perform(get("/admin/v1/menu-dates/" + dateId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bakeDate").value(bake.toString()));
    }

    @Test
    void publicNotFoundResponsesAreNotCacheable() throws Exception {
        String unknown = java.util.UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/menu-dates/" + unknown))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/api/v1/offerings/" + unknown))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    // -- helpers ----------------------------------------------------------

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where action = ?", Integer.class, action);
    }

    private LocalDate futureBakeDate(int days) {
        return LocalDate.now(ZONE).plusDays(days);
    }

    private String uploadMedia() throws Exception {
        MvcResult result = mvc.perform(multipart("/admin/v1/media")
                .file(new MockMultipartFile("file", "roll.png", "image/png", CatalogTestImages.png(900, 600)))
                .headers(adminHeaders()))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createTemplate(String name, String groupName, String mediaId) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(templateBody(name, groupName, mediaId)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String templateBody(String name, String groupName, String mediaId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("active", true);
        if (mediaId != null) body.put("photoMediaId", mediaId);
        ArrayNode groups = body.putArray("customizationGroups");
        ObjectNode group = groups.addObject();
        group.put("name", groupName);
        group.put("selectionType", "SINGLE");
        group.put("required", true);
        group.put("minSelections", 1);
        group.put("maxSelections", 1);
        ArrayNode options = group.putArray("options");
        ObjectNode option = options.addObject();
        option.put("name", "Option A");
        option.put("priceDeltaCents", 50);
        return json(body);
    }

    private String createMenu(String title) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String addDate(String menuId, LocalDate bakeDate) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus/" + menuId + "/dates")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + bakeDate + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createWindow(String dateId, LocalDate bakeDate, int startHour, int endHour, int maxOrders)
            throws Exception {
        Instant start = bakeDate.atTime(startHour, 0).atZone(ZONE).toInstant();
        Instant end = bakeDate.atTime(endHour, 0).atZone(ZONE).toInstant();
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/pickup-windows")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Morning\",\"startAt\":\"" + start + "\",\"endAt\":\"" + end
                    + "\",\"maxOrders\":" + maxOrders + "}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createOffering(String dateId, String templateId, List<String> windowIds, int price,
            int quantity, Integer maxPerCustomer, String photoMediaId) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(offeringBody(templateId, windowIds, price, quantity, maxPerCustomer, photoMediaId)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String offeringBody(String templateId, List<String> windowIds, int price, int quantity,
            Integer maxPerCustomer, String photoMediaId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("itemTemplateId", templateId);
        body.put("priceCents", price);
        body.put("quantityLimit", quantity);
        if (maxPerCustomer != null) body.put("maxPerCustomer", maxPerCustomer);
        if (photoMediaId != null) body.put("photoMediaId", photoMediaId);
        ArrayNode windows = body.putArray("windowIds");
        windowIds.forEach(windows::add);
        return json(body);
    }

    private org.springframework.test.web.servlet.ResultActions publish(String menuId) throws Exception {
        return mvc.perform(post("/admin/v1/menus/" + menuId + "/publish")
            .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"notificationEnabled\":true}"));
    }
}
