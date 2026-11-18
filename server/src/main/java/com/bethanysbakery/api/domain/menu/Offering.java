package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "offering")
public class Offering {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_date_id", nullable = false)
    private MenuDate menuDate;

    @Column(name = "item_template_id", nullable = false)
    private UUID itemTemplateId;

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

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(name = "quantity_limit", nullable = false)
    private int quantityLimit;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Column(name = "max_per_customer")
    private Integer maxPerCustomer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActiveStatus status;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, id ASC")
    private List<OfferingOptionGroup> groups = new ArrayList<>();

    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<OfferingWindow> windows = new LinkedHashSet<>();

    protected Offering() {}

    public Offering(UUID id, MenuDate menuDate, UUID itemTemplateId, String name, String description,
            UUID photoMediaId, String ingredientsText, List<String> allergenTags, int priceCents,
            int quantityLimit, Integer maxPerCustomer, Instant createdAt) {
        this.id = id;
        this.menuDate = menuDate;
        this.itemTemplateId = itemTemplateId;
        this.name = name;
        this.description = description;
        this.photoMediaId = photoMediaId;
        this.ingredientsText = ingredientsText;
        this.allergenTags = new ArrayList<>(allergenTags);
        this.priceCents = priceCents;
        this.quantityLimit = quantityLimit;
        this.maxPerCustomer = maxPerCustomer;
        this.reservedCount = 0;
        this.status = ActiveStatus.ACTIVE;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public MenuDate getMenuDate() { return menuDate; }
    public UUID getMenuDateId() { return menuDate.getId(); }
    public UUID getItemTemplateId() { return itemTemplateId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getPhotoMediaId() { return photoMediaId; }
    public String getIngredientsText() { return ingredientsText; }
    public List<String> getAllergenTags() { return allergenTags; }
    public int getPriceCents() { return priceCents; }
    public int getQuantityLimit() { return quantityLimit; }
    public int getReservedCount() { return reservedCount; }
    public Integer getMaxPerCustomer() { return maxPerCustomer; }
    public ActiveStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OfferingOptionGroup> getGroups() { return groups; }
    public Set<OfferingWindow> getWindows() { return windows; }
    public int availableQuantity() { return Math.max(0, quantityLimit - reservedCount); }
    public boolean isActive() { return status == ActiveStatus.ACTIVE; }

    public void update(String name, String description, UUID photoMediaId, String ingredientsText,
            List<String> allergenTags, int priceCents, int quantityLimit, Integer maxPerCustomer, Instant now) {
        this.name = name;
        this.description = description;
        this.photoMediaId = photoMediaId;
        this.ingredientsText = ingredientsText;
        this.allergenTags = new ArrayList<>(allergenTags);
        this.priceCents = priceCents;
        this.quantityLimit = quantityLimit;
        this.maxPerCustomer = maxPerCustomer;
        this.updatedAt = now;
    }

    public void replaceGroups(List<OfferingOptionGroup> desired) {
        groups.clear();
        for (OfferingOptionGroup group : desired) {
            group.attachTo(this);
            groups.add(group);
        }
    }

    public void replaceWindows(List<PickupWindow> pickupWindows) {
        windows.clear();
        for (PickupWindow pickupWindow : pickupWindows) {
            windows.add(new OfferingWindow(this, pickupWindow));
        }
    }

    public void cancel(Instant now) {
        this.status = ActiveStatus.CANCELLED;
        this.updatedAt = now;
    }
}
