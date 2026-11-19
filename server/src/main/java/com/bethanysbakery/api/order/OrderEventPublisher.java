package com.bethanysbakery.api.order;

import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.order.Actor;
import com.bethanysbakery.api.domain.order.Order;

/** Domain events for order lifecycle transitions. S6 implements notification handling. */
public interface OrderEventPublisher {

    void orderCreated(Order order);

    void orderAccepted(Order order);

    void orderRejected(Order order, String reason);

    void orderReady(Order order);

    void orderCompleted(Order order);

    void orderCancelled(Order order, String reason, Actor actor);

    void orderExpired(Order order);

    void menuDateCancelled(MenuDate date, String reason);
}
