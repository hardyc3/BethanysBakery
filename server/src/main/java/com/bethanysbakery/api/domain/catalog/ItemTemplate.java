package com.bethanysbakery.api.domain.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "item_template")
public class ItemTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "photo_media_id")
    private UUID photoMediaId;

    @Column(name = "ingredients_text")
    private String ingredientsText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allergen_tags", nullable = false, columnDefinition = "jsonb")
    private List<String> allergenTags = new ArrayList<>();

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, id ASC")
    private List<CustomizationGroup> groups = new ArrayList<>();

    protected ItemTemplate() {}

    public ItemTemplate(UUID id, String name, String description, UUID photoMediaId, String ingredientsText,
            List<String> allergenTags, boolean active, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.photoMediaId = photoMediaId;
        this.ingredientsText = ingredientsText;
        this.allergenTags = new ArrayList<>(allergenTags);
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getPhotoMediaId() { return photoMediaId; }
    public String getIngredientsText() { return ingredientsText; }
    public List<String> getAllergenTags() { return allergenTags; }
    public boolean isActive() { return active; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<CustomizationGroup> getGroups() { return groups; }

    public void update(String name, String description, UUID photoMediaId, String ingredientsText,
            List<String> allergenTags, boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.photoMediaId = photoMediaId;
        this.ingredientsText = ingredientsText;
        this.allergenTags = new ArrayList<>(allergenTags);
        this.active = active;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public void replaceGroups(List<CustomizationGroup> desired) {
        groups.clear();
        for (CustomizationGroup group : desired) {
            group.attachTo(this);
            groups.add(group);
        }
    }
}
