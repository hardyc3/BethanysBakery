package com.bethanysbakery.api.domain.menu;

import com.bethanysbakery.api.domain.catalog.SelectionType;
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
@Table(name = "offering_option_group")
public class OfferingOptionGroup {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id", nullable = false)
    private Offering offering;

    @Column(name = "source_group_id")
    private UUID sourceGroupId;

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

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, id ASC")
    private List<OfferingOption> options = new ArrayList<>();

    protected OfferingOptionGroup() {}

    public OfferingOptionGroup(UUID id, UUID sourceGroupId, String name, SelectionType selectionType,
            boolean required, int minSelections, int maxSelections, int sortOrder) {
        this.id = id;
        this.sourceGroupId = sourceGroupId;
        this.name = name;
        this.selectionType = selectionType;
        this.required = required;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public Offering getOffering() { return offering; }
    public UUID getSourceGroupId() { return sourceGroupId; }
    public String getName() { return name; }
    public SelectionType getSelectionType() { return selectionType; }
    public boolean isRequired() { return required; }
    public int getMinSelections() { return minSelections; }
    public int getMaxSelections() { return maxSelections; }
    public int getSortOrder() { return sortOrder; }
    public List<OfferingOption> getOptions() { return options; }

    public void update(String name, SelectionType selectionType, boolean required, int minSelections,
            int maxSelections, int sortOrder) {
        this.name = name;
        this.selectionType = selectionType;
        this.required = required;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
        this.sortOrder = sortOrder;
    }

    public void replaceOptions(List<OfferingOption> desired) {
        options.clear();
        for (OfferingOption option : desired) {
            option.attachTo(this);
            options.add(option);
        }
    }

    public void attachTo(Offering offering) {
        this.offering = offering;
    }
}
