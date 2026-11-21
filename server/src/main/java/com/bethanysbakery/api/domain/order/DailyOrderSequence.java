package com.bethanysbakery.api.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "daily_order_sequence")
public class DailyOrderSequence {

    @Id
    @Column(name = "bake_date", nullable = false)
    private LocalDate bakeDate;

    @Column(name = "next_value", nullable = false)
    private int nextValue;

    protected DailyOrderSequence() {}

    public DailyOrderSequence(LocalDate bakeDate, int nextValue) {
        this.bakeDate = bakeDate;
        this.nextValue = nextValue;
    }

    public LocalDate getBakeDate() { return bakeDate; }
    public int getNextValue() { return nextValue; }
}
