package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.common.error.Problem.FieldError;
import com.bethanysbakery.api.common.error.PublishValidationException;
import com.bethanysbakery.api.domain.catalog.SelectionType;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingOption;
import com.bethanysbakery.api.domain.menu.OfferingOptionGroup;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.OfferingWindow;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Publish-time invariants. Collects all field errors before returning a 409 problem. */
@Component
public class MenuPublishValidator {

    private final MenuTimeRules time;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;

    public MenuPublishValidator(MenuTimeRules time, PickupWindowRepository windows, OfferingRepository offerings) {
        this.time = time;
        this.windows = windows;
        this.offerings = offerings;
    }

    public void validate(Menu menu, List<MenuDate> dates) {
        List<FieldError> errors = new ArrayList<>();
        List<MenuDate> activeDates = dates.stream().filter(MenuDate::isActive).toList();
        if (activeDates.isEmpty()) {
            errors.add(new FieldError("dates", "at least one non-cancelled date is required"));
        }

        for (MenuDate date : activeDates) {
            String dateField = "dates[" + date.getId() + "]";
            if (date.getBakeDate().isBefore(time.today())) {
                errors.add(new FieldError(dateField + ".bakeDate", "must be today or later in " + time.zone()));
            }

            List<PickupWindow> activeWindows = windows.findAllByMenuDate_IdOrderByStartAtAsc(date.getId())
                .stream().filter(PickupWindow::isActive).toList();
            if (activeWindows.isEmpty()) {
                errors.add(new FieldError(dateField + ".windows", "at least one active window is required"));
            }
            Set<UUID> activeWindowIds = new HashSet<>();
            for (PickupWindow window : activeWindows) {
                activeWindowIds.add(window.getId());
                if (!time.fallsOnBakeDate(window.getStartAt(), date.getBakeDate())
                        || !time.fallsOnBakeDate(window.getEndAt(), date.getBakeDate())) {
                    errors.add(new FieldError(dateField + ".windows[" + window.getId() + "]",
                        "pickup window does not fall on the bake date in " + time.zone()));
                }
            }

            List<Offering> activeOfferings = offerings.findAllByMenuDate_IdOrderByNameAsc(date.getId())
                .stream().filter(Offering::isActive).toList();
            if (activeOfferings.isEmpty()) {
                errors.add(new FieldError(dateField + ".offerings", "at least one active offering is required"));
            }
            for (Offering offering : activeOfferings) {
                String offeringField = dateField + ".offerings[" + offering.getId() + "]";
                validateAssignments(offering, activeWindowIds, offeringField, errors);
                validateGroups(offering, offeringField, errors);
            }
        }

        if (!errors.isEmpty()) throw new PublishValidationException(errors);
    }

    private void validateAssignments(Offering offering, Set<UUID> activeWindowIds,
            String field, List<FieldError> errors) {
        boolean hasActiveAssignment = false;
        for (OfferingWindow assignment : offering.getWindows()) {
            UUID windowId = assignment.getPickupWindowId();
            if (!activeWindowIds.contains(windowId)) {
                errors.add(new FieldError(field + ".windowIds",
                    "assigned window " + windowId + " is not active on this date"));
            } else {
                hasActiveAssignment = true;
            }
        }
        if (offering.getWindows().isEmpty()) {
            errors.add(new FieldError(field + ".windowIds", "offering must be assigned to at least one window"));
        } else if (!hasActiveAssignment) {
            errors.add(new FieldError(field + ".windowIds",
                "offering must be assigned to at least one active window on this date"));
        }
    }

    private void validateGroups(Offering offering, String field, List<FieldError> errors) {
        List<OfferingOptionGroup> groups = offering.getGroups();
        if (groups.size() > 20) {
            errors.add(new FieldError(field + ".customizationGroups", "must contain at most 20 groups"));
        }
        Set<String> groupNames = new HashSet<>();
        for (int g = 0; g < groups.size(); g++) {
            OfferingOptionGroup group = groups.get(g);
            String groupField = field + ".customizationGroups[" + g + "]";
            if (!groupNames.add(group.getName().toLowerCase(Locale.ROOT))) {
                errors.add(new FieldError(groupField + ".name", "duplicate group name"));
            }
            if (group.getMinSelections() < 0 || group.getMaxSelections() < 1
                    || group.getMinSelections() > group.getMaxSelections()) {
                errors.add(new FieldError(groupField, "minSelections/maxSelections range is invalid"));
            }
            if (group.getSelectionType() == SelectionType.SINGLE && group.getMaxSelections() != 1) {
                errors.add(new FieldError(groupField + ".maxSelections", "SINGLE requires maxSelections = 1"));
            }
            List<OfferingOption> options = group.getOptions();
            if (options.isEmpty() || options.size() > 50) {
                errors.add(new FieldError(groupField + ".options", "must contain 1-50 options"));
            }
            Set<String> optionNames = new HashSet<>();
            for (OfferingOption option : options) {
                if (!optionNames.add(option.getName().toLowerCase(Locale.ROOT))) {
                    errors.add(new FieldError(groupField + ".options", "duplicate option name in group"));
                }
            }
        }
    }
}
