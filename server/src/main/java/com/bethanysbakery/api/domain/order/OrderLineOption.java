package com.bethanysbakery.api.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_line_option")
public class OrderLineOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine line;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "price_delta_cents", nullable = false)
    private int priceDeltaCents;

    protected OrderLineOption() {}

    public OrderLineOption(UUID id, OrderLine line, String groupName, String optionName, int priceDeltaCents) {
        this.id = id;
        this.line = line;
        this.groupName = groupName;
        this.optionName = optionName;
        this.priceDeltaCents = priceDeltaCents;
    }

    public UUID getId() { return id; }
    public OrderLine getLine() { return line; }
    public String getGroupName() { return groupName; }
    public String getOptionName() { return optionName; }
    public int getPriceDeltaCents() { return priceDeltaCents; }
}
