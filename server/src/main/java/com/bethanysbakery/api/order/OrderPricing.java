package com.bethanysbakery.api.order;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingOption;
import com.bethanysbakery.api.domain.menu.OfferingOptionGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates offering customizations and computes order line totals from trusted snapshots. */
@Component
public class OrderPricing {

    public PricedLine price(Offering offering, List<UUID> optionIds, int quantity, String notes) {
        Map<UUID, OfferingOptionGroup> groupsByOption = new HashMap<>();
        Map<UUID, OfferingOption> optionsById = new HashMap<>();
        for (OfferingOptionGroup group : offering.getGroups()) {
            for (OfferingOption option : group.getOptions()) {
                groupsByOption.put(option.getId(), group);
                optionsById.put(option.getId(), option);
            }
        }

        List<UUID> requested = optionIds == null ? List.of() : optionIds;
        Set<UUID> distinct = new HashSet<>(requested);
        if (distinct.size() != requested.size()) {
            throw invalid("duplicate option id");
        }

        Map<UUID, Integer> selectionsPerGroup = new LinkedHashMap<>();
        List<SelectedOption> selected = new ArrayList<>();
        int deltaSum = 0;
        for (UUID optionId : requested) {
            OfferingOption option = optionsById.get(optionId);
            OfferingOptionGroup group = groupsByOption.get(optionId);
            if (option == null || group == null) {
                throw invalid("unknown or cross-offering option id");
            }
            if (!option.isAvailable()) {
                throw invalid("unavailable option");
            }
            int count = selectionsPerGroup.merge(group.getId(), 1, Integer::sum);
            if (count > group.getMaxSelections()) {
                throw invalid("too many selections for " + group.getName());
            }
            deltaSum += option.getPriceDeltaCents();
            selected.add(new SelectedOption(group.getName(), option.getName(), option.getPriceDeltaCents()));
        }
        for (OfferingOptionGroup group : offering.getGroups()) {
            int count = selectionsPerGroup.getOrDefault(group.getId(), 0);
            if (group.isRequired() && count == 0) {
                throw invalid("required group has no selection: " + group.getName());
            }
            if (count < group.getMinSelections()) {
                throw invalid("not enough selections for " + group.getName());
            }
        }

        int lineTotal = (offering.getPriceCents() + deltaSum) * quantity;
        return new PricedLine(offering.getId(), offering.getName(), offering.getPriceCents(), quantity,
            lineTotal, notes, selected);
    }

    private static AuthException invalid(String detail) {
        return AuthException.badRequest("INVALID_CUSTOMIZATION", "Invalid customization: " + detail);
    }

    public record PricedLine(UUID offeringId, String itemName, int unitPriceCents, int quantity, int lineTotalCents,
            String notes, List<SelectedOption> selected) {}

    public record SelectedOption(String groupName, String optionName, int priceDeltaCents) {}
}
