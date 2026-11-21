package com.bethanysbakery.api.order;

import com.bethanysbakery.api.menu.MenuCancellationHook;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S4 cancellation hook: cancel active orders and release inventory for a cancelled bake date. */
@Component
public class OrderMenuCancellationHook implements MenuCancellationHook {

    private final OrderService orders;

    public OrderMenuCancellationHook(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public void onMenuDateCancelled(UUID menuDateId) {
        orders.cancelForMenuDate(menuDateId);
    }
}
