package com.bethanysbakery.api.domain.catalog;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customization_group")
public class CustomizationGroup {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_template_id", nullable = false)
    private ItemTemplate template;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_type", nullable = false)
    private SelectionType selectionType;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "min_selections", nullable = false)
    private int minSelections;

    @Column(name = "max_selections", nullable = false)
    private int maxSelections;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<CustomizationOption> options = new ArrayList<>();

    protected CustomizationGroup() {}

    public CustomizationGroup(UUID id, String name, SelectionType selectionType, boolean required,
            int minSelections, int maxSelections, int sortOrder) {
        this.id = id;
        this.name = name;
        this.selectionType = selectionType;
        this.required = required;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public ItemTemplate getTemplate() { return template; }
    public String getName() { return name; }
    public SelectionType getSelectionType() { return selectionType; }
    public boolean isRequired() { return required; }
    public int getMinSelections() { return minSelections; }
    public int getMaxSelections() { return maxSelections; }
    public int getSortOrder() { return sortOrder; }
    public List<CustomizationOption> getOptions() { return options; }

    public void update(String name, SelectionType selectionType, boolean required, int minSelections,
            int maxSelections, int sortOrder) {
        this.name = name;
        this.selectionType = selectionType;
        this.required = required;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
        this.sortOrder = sortOrder;
    }

    public void attachTo(ItemTemplate itemTemplate) {
        this.template = itemTemplate;
    }

    public void replaceOptions(List<CustomizationOption> desired) {
        options.clear();
        for (CustomizationOption option : desired) {
            options.add(option);
        }
    }
}
