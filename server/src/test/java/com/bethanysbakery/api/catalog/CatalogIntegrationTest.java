package com.bethanysbakery.api.catalog;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class CatalogIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";

    private String accessToken;
    private String deviceKey;
    private String totpSecret;

    @BeforeEach
    void authenticateAdmin() throws Exception {
        var admin = seedAdmin("catalog@example.com", PASSWORD, List.of());
        var device = seedDevice(admin.id(), "catalog-device-key", "Pixel");
        deviceKey = device.apiKey();
        totpSecret = admin.totpSecret();
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody()))
            .andExpect(status().isOk())
            .andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    @Test
    void createReturnsSortedGroupsAndOptions() throws Exception {
        ObjectNode groupA = group("Toppings", "MULTI", true, 1, 3, 20,
            option("Sprinkles", 50, 2), option("Cherries", 75, 1));
        ObjectNode groupB = group("Milk", "SINGLE", true, 1, 1, 10,
            option("Whole", 0, 1));

        MvcResult result = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Cinnamon Roll", true, groupA, groupB)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Cinnamon Roll"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.customizationGroups[0].name").value("Milk"))
            .andExpect(jsonPath("$.customizationGroups[1].name").value("Toppings"))
            .andExpect(jsonPath("$.customizationGroups[1].options[0].name").value("Cherries"))
            .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/admin/v1/item-templates/" + id).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Cinnamon Roll"));
    }

    @Test
    void rejectsInvalidCustomizationRules() throws Exception {
        mvc.perform(post("/admin/v1/item-templates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Bad single", true,
                    group("Size", "SINGLE", true, 1, 2, 0, option("Large", 0, 0)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(post("/admin/v1/item-templates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Bad range", true,
                    group("Toppings", "MULTI", false, 3, 2, 0, option("A", 0, 0)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/admin/v1/item-templates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Duplicate names", true,
                    group("Toppings", "MULTI", false, 0, 2, 0,
                        option("A", 0, 0), option("a", 10, 1)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listActiveExcludesInactiveAndDeleteDeactivates() throws Exception {
        String activeId = createTemplate("Active", true);
        createTemplate("Inactive", false);

        mvc.perform(get("/admin/v1/item-templates").param("active", "true").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(activeId));

        mvc.perform(delete("/admin/v1/item-templates/" + activeId).headers(adminHeaders()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/admin/v1/item-templates").param("active", "true").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/admin/v1/item-templates/" + activeId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void replaceFullTemplatePreservesSuppliedIdsAndRemovesOldNestedRows() throws Exception {
        ObjectNode keptGroup = group("Keep", "MULTI", false, 0, 2, 0, option("KeepOpt", 10, 0));
        ObjectNode droppedGroup = group("Drop", "MULTI", false, 0, 2, 1,
            option("DropOptA", 10, 0), option("DropOptB", 10, 1));
        MvcResult created = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Original", true, keptGroup, droppedGroup)))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode before = objectMapper.readTree(created.getResponse().getContentAsString());
        String templateId = before.get("id").asText();
        String keptGroupId = before.at("/customizationGroups/0/id").asText();
        String keptOptionId = before.at("/customizationGroups/0/options/0/id").asText();
        String droppedGroupId = before.at("/customizationGroups/1/id").asText();
        String droppedOptionId = before.at("/customizationGroups/1/options/0/id").asText();

        ObjectNode replacement = group("Keep", "MULTI", false, 0, 2, 0, option("KeptOpt", 25, 0));
        replacement.put("id", keptGroupId);
        ((ObjectNode) replacement.at("/options/0")).put("id", keptOptionId);

        mvc.perform(put("/admin/v1/item-templates/" + templateId)
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Renamed", true, replacement)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(jsonPath("$.customizationGroups.length()").value(1))
            .andExpect(jsonPath("$.customizationGroups[0].id").value(keptGroupId))
            .andExpect(jsonPath("$.customizationGroups[0].options.length()").value(1))
            .andExpect(jsonPath("$.customizationGroups[0].options[0].id").value(keptOptionId))
            .andExpect(jsonPath("$.customizationGroups[0].options[0].name").value("KeptOpt"));

        assertThat(jdbc.queryForObject("select count(*) from customization_group", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customization_option", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customization_group where id = ?::uuid",
            Integer.class, droppedGroupId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customization_option where id = ?::uuid",
            Integer.class, droppedOptionId)).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ITEM_TEMPLATE_REPLACED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void replaceRejectsUnknownAndDuplicateNestedIds() throws Exception {
        MvcResult created = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Original", true,
                    group("Toppings", "MULTI", false, 0, 2, 0, option("A", 0, 0)))))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode before = objectMapper.readTree(created.getResponse().getContentAsString());
        String templateId = before.get("id").asText();
        String groupId = before.at("/customizationGroups/0/id").asText();

        ObjectNode unknownGroup = group("Bogus", "MULTI", false, 0, 2, 0, option("A", 0, 0));
        unknownGroup.put("id", UUID.randomUUID().toString());
        mvc.perform(put("/admin/v1/item-templates/" + templateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Unknown group", true, unknownGroup)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("unknown nested group id")));

        ObjectNode unknownOption = group("Toppings", "MULTI", false, 0, 2, 0, option("A", 0, 0));
        unknownOption.put("id", groupId);
        ((ObjectNode) unknownOption.at("/options/0")).put("id", UUID.randomUUID().toString());
        mvc.perform(put("/admin/v1/item-templates/" + templateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Unknown option", true, unknownOption)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("unknown nested option id")));

        ObjectNode duplicateOptionIds = group("Toppings", "MULTI", false, 0, 2, 0,
            option("A", 0, 0), option("B", 10, 1));
        String sharedId = UUID.randomUUID().toString();
        ((ObjectNode) duplicateOptionIds.at("/options/0")).put("id", sharedId);
        ((ObjectNode) duplicateOptionIds.at("/options/1")).put("id", sharedId);
        mvc.perform(put("/admin/v1/item-templates/" + templateId).headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template("Duplicate option ids", true, duplicateOptionIds)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].message").value(containsString("duplicate option id")));
    }

    @Test
    void uploadNormalizesImageAndServesVariantsWithCacheHeaders() throws Exception {
        MvcResult upload = upload(CatalogTestImages.jpegWithExif(2200, 1400), "x.jpg", "image/jpeg");
        JsonNode media = objectMapper.readTree(upload.getResponse().getContentAsString());
        String id = media.get("id").asText();

        assertThat(media.get("height").asInt()).isEqualTo(1600);
        assertThat(media.get("width").asInt()).isLessThan(1600);
        assertThat(media.at("/variants/400").asText()).contains("size=400");
        assertThat(media.at("/variants/1600").asText()).contains("size=1600");

        MvcResult original = mvc.perform(get("/api/v1/media/" + id))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"))
            .andExpect(header().string("Cache-Control", containsString("immutable")))
            .andExpect(header().exists("ETag"))
            .andReturn();
        String etag = original.getResponse().getHeader("ETag");

        mvc.perform(get("/api/v1/media/" + id).param("size", "400"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"));
        mvc.perform(get("/api/v1/media/" + id).header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag));
    }

    @Test
    void uploadsWebpAndPersistsNormalizedMetadata() throws Exception {
        MvcResult result = upload(CatalogTestImages.webp(900, 700), "photo.webp", "image/webp");
        JsonNode media = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(media.get("width").asInt()).isEqualTo(900);
        assertThat(media.get("height").asInt()).isEqualTo(700);
        assertThat(media.get("variants").size()).isEqualTo(2);
        assertThat(media.at("/variants/800").asText()).contains("size=800");
        mvc.perform(get("/api/v1/media/" + media.get("id").asText()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"));
    }

    @Test
    void rejectsInvalidVariantSize() throws Exception {
        String id = mediaId(upload(CatalogTestImages.png(900, 600), "size.png", "image/png"));

        mvc.perform(get("/api/v1/media/" + id).param("size", "123"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void servesOriginalWhenRequestedVariantIsUnavailable() throws Exception {
        String id = mediaId(upload(CatalogTestImages.png(600, 400), "small.png", "image/png"));

        MvcResult response = mvc.perform(get("/api/v1/media/" + id).param("size", "800"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/webp"))
            .andExpect(header().exists("ETag"))
            .andReturn();
        assertThat(response.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void rejectsOversizedAndSpoofedUploads() throws Exception {
        byte[] oversized = new byte[12 * 1024 * 1024];
        mvc.perform(multipart("/admin/v1/media")
                .file(new MockMultipartFile("file", "big.jpg", "image/jpeg", oversized))
                .headers(adminHeaders()))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/admin/v1/media")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", svg))
                .headers(adminHeaders()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void mediaInUseConflictAndUnusedDeleteRemovesFiles() throws Exception {
        String usedId = mediaId(upload(CatalogTestImages.png(1200, 800), "used.png", "image/png"));
        createTemplateWithPhoto("Uses photo", usedId);

        mvc.perform(delete("/admin/v1/media/" + usedId).headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MEDIA_IN_USE"));

        MvcResult upload = upload(CatalogTestImages.png(900, 600), "unused.png", "image/png");
        String unusedId = mediaId(upload);
        String storageKey = jdbc.queryForObject("select storage_key from media where id = ?::uuid", String.class, unusedId);
        Path mediaRoot = Path.of("./build/test-media");
        assertThat(mediaRoot.resolve(storageKey)).exists();

        mvc.perform(delete("/admin/v1/media/" + unusedId).headers(adminHeaders()))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select status from media where id = ?::uuid", String.class, unusedId))
            .isEqualTo("DELETED");
        assertThat(mediaRoot.resolve(storageKey)).doesNotExist();
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'MEDIA_DELETED'", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/media/" + unusedId)).andExpect(status().isNotFound());
    }

    @Test
    void uploadFilenameNeverControlsStoragePath() throws Exception {
        MvcResult result = upload(CatalogTestImages.jpeg(900, 600), "../../evil.jpg", "image/jpeg");
        JsonNode media = objectMapper.readTree(result.getResponse().getContentAsString());
        String storageKey = jdbc.queryForObject("select storage_key from media where id = ?::uuid", String.class,
            media.get("id").asText());

        assertThat(storageKey).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}/original\\.webp");
        assertThat(Path.of("./build/evil.jpg")).doesNotExist();
        assertThat(jdbc.queryForObject("select original_filename from media where id = ?::uuid",
            String.class, media.get("id").asText())).doesNotContain("../");
    }

    @Test
    void mutationsWriteAuditEvents() throws Exception {
        String mediaId = mediaId(upload(CatalogTestImages.jpeg(900, 600), "audit.jpg", "image/jpeg"));
        String templateId = createTemplateWithPhoto("Audited", mediaId);
        mvc.perform(delete("/admin/v1/item-templates/" + templateId).headers(adminHeaders()))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'MEDIA_UPLOADED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ITEM_TEMPLATE_CREATED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ITEM_TEMPLATE_DEACTIVATED'", Integer.class)).isEqualTo(1);
    }

    // -- helpers ----------------------------------------------------------

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }

    private String loginBody() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", "catalog@example.com");
        body.put("password", PASSWORD);
        body.put("totpCode", currentTotpCode(totpSecret));
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult upload(byte[] bytes, String filename, String contentType) throws Exception {
        return mvc.perform(multipart("/admin/v1/media")
                .file(new MockMultipartFile("file", filename, contentType, bytes))
                .headers(adminHeaders()))
            .andExpect(status().isCreated())
            .andReturn();
    }

    private String mediaId(MvcResult upload) throws Exception {
        return objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();
    }

    private String createTemplate(String name, boolean active) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template(name, active,
                    group("Toppings", "MULTI", false, 0, 2, 0, option("A", 0, 0)))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createTemplateWithPhoto(String name, String mediaId) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(template(name, true,
            group("Toppings", "MULTI", false, 0, 2, 0, option("A", 0, 0))));
        body.put("photoMediaId", mediaId);
        MvcResult result = mvc.perform(post("/admin/v1/item-templates")
                .headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String template(String name, boolean active, ObjectNode... groups) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("active", active);
        ArrayNode groupArray = body.putArray("customizationGroups");
        for (ObjectNode group : groups) groupArray.add(group);
        return objectMapper.writeValueAsString(body);
    }

    private ObjectNode group(String name, String type, boolean required, int min, int max, int sortOrder,
            ObjectNode... options) {
        ObjectNode group = objectMapper.createObjectNode();
        group.put("name", name);
        group.put("selectionType", type);
        group.put("required", required);
        group.put("minSelections", min);
        group.put("maxSelections", max);
        group.put("sortOrder", sortOrder);
        ArrayNode optionArray = group.putArray("options");
        for (ObjectNode option : options) optionArray.add(option);
        return group;
    }

    private ObjectNode option(String name, int price, int sortOrder) {
        ObjectNode option = objectMapper.createObjectNode();
        option.put("name", name);
        option.put("priceDeltaCents", price);
        option.put("sortOrder", sortOrder);
        option.put("available", true);
        return option;
    }
}
