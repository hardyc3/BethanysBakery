package com.bethanysbakery.api.menu;

import java.util.UUID;

/**
 * Extension point for downstream cancellation side effects. S4 only flips menu/date/window/offering
 * statuses; S5 implements this interface to cancel active orders and release inventory when a menu
 * date is cancelled. Keeping the hook lets S4 cancellation logic stay stable when orders arrive.
 */
public interface MenuCancellationHook {

    /** Called after a menu date (and its windows/offerings) is cancelled in the same transaction. */
    void onMenuDateCancelled(UUID menuDateId);
}
