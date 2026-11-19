package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingOption;
import com.bethanysbakery.api.domain.menu.OfferingOptionGroup;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure customization and total rules. Prices always come from offering snapshots. */
class OrderPricingTest {

    private final OrderPricing pricing = new OrderPricing();

    @Test
    void quantityTwoOfFourDollarsIsEightHundredCents() {
        Offering offering = offering(400, List.of());
        assertThat(pricing.price(offering, List.of(), 2, null).lineTotalCents()).isEqualTo(800);
    }

    @Test
    void dollarOptionTimesTwoMakesLineTotalTenDollars() {
        Offering offering = offering(400, List.of(option("Topping", 100)));
        UUID optionId = offering.getGroups().get(0).getOptions().get(0).getId();

        assertThat(pricing.price(offering, List.of(optionId), 2, null).lineTotalCents()).isEqualTo(1000);
    }

    @Test
    void missingRequiredSelectionIsRejected() {
        Offering offering = offering(400, List.of(option("Icing", 50)));
        assertThatThrownBy(() -> pricing.price(offering, List.of(), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    @Test
    void requiredGroupWithZeroMinimumStillRejectsNoSelection() {
        Offering offering = offering(400, List.of(option("Icing", 50)), true, 0, 1);
        assertThatThrownBy(() -> pricing.price(offering, List.of(), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    @Test
    void optionalGroupWithZeroMinimumAllowsNoSelection() {
        Offering offering = offering(400, List.of(option("Icing", 50)), false, 0, 1);
        assertThat(pricing.price(offering, List.of(), 1, null).lineTotalCents()).isEqualTo(400);
    }

    @Test
    void optionFromAnotherOfferingIsRejected() {
        Offering offering = offering(400, List.of());
        Offering other = offering(500, List.of(option("Foreign Icing", 75)));
        UUID foreignOptionId = other.getGroups().get(0).getOptions().get(0).getId();
        assertThatThrownBy(() -> pricing.price(offering, List.of(foreignOptionId), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    @Test
    void unavailableAndDuplicateOptionsAreRejected() {
        OfferingOption unavailable = new OfferingOption(Uuids.v7(), null, null, "Off", 0, false, 0);
        Offering offering = offering(400, List.of(unavailable));
        UUID id = offering.getGroups().get(0).getOptions().get(0).getId();
        assertThatThrownBy(() -> pricing.price(offering, List.of(id), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
        assertThatThrownBy(() -> pricing.price(offering, List.of(id, id), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    @Test
    void moreSelectionsThanGroupMaximumAreRejected() {
        Offering offering = offering(400, List.of(option("A", 0), option("B", 0)), true, 1, 1);
        List<UUID> ids = offering.getGroups().get(0).getOptions().stream()
            .map(OfferingOption::getId).toList();
        assertThatThrownBy(() -> pricing.price(offering, ids, 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    @Test
    void fewerSelectionsThanGroupMinimumAreRejected() {
        Offering offering = offering(400, List.of(option("A", 0), option("B", 0)), false, 2, 2);
        UUID one = offering.getGroups().get(0).getOptions().get(0).getId();
        assertThatThrownBy(() -> pricing.price(offering, List.of(one), 1, null))
            .isInstanceOf(AuthException.class)
            .satisfies(ex -> assertThat(((AuthException) ex).code()).isEqualTo("INVALID_CUSTOMIZATION"));
    }

    private static OfferingOption option(String name, int delta) {
        return new OfferingOption(Uuids.v7(), null, null, name, delta, true, 0);
    }

    private static Offering offering(int priceCents, List<OfferingOption> options) {
        return offering(priceCents, options, true, 1, 1);
    }

    private static Offering offering(int priceCents, List<OfferingOption> options,
            boolean required, int minSelections, int maxSelections) {
        Instant now = Instant.now();
        Menu menu = new Menu(Uuids.v7(), "Menu", null, Uuids.v7(), now);
        menu.publish(true, now, now);
        MenuDate date = new MenuDate(Uuids.v7(), menu, LocalDate.now().plusDays(5), now);
        date.publish(now);
        Offering offering = new Offering(Uuids.v7(), date, Uuids.v7(), "Roll", null, null, null,
            List.of(), priceCents, 10, null, now);
        if (!options.isEmpty()) {
            OfferingOptionGroup group = new OfferingOptionGroup(Uuids.v7(), null, "Toppings",
                SelectionType.SINGLE, required, minSelections, maxSelections, 0);
            group.replaceOptions(options);
            offering.replaceGroups(List.of(group));
        }
        return offering;
    }
}
