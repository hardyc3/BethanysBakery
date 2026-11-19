package com.bethanysbakery.api.order;

import com.bethanysbakery.api.api.order.OrderApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.domain.menu.Offering;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.menu.PickupWindowRepository;
import com.bethanysbakery.api.domain.order.Actor;
import com.bethanysbakery.api.domain.order.CancelledBy;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.domain.order.PaymentStatus;
import com.bethanysbakery.api.menu.MenuTimeRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order creation, lifecycle, reads, expiry, menu-cancellation propagation, and admin aggregates. */
@Service
public class OrderService {

    private static final List<OrderStatus> ACTIVE = Arrays.stream(OrderStatus.values())
        .filter(OrderStatus::isActive).toList();
    private static final List<OrderStatus> TERMINAL_FAILURES = Arrays.stream(OrderStatus.values())
        .filter(OrderStatus::isTerminalFailure).toList();
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final MenuDateRepository dates;
    private final PickupWindowRepository windows;
    private final OfferingRepository offerings;
    private final MenuTimeRules time;
    private final OrderPricing pricing;
    private final InventoryService inventory;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final OrderEventPublisher publisher;
    private final OrderViews views;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public OrderService(OrderRepository orders, CustomerRepository customers, MenuDateRepository dates,
            PickupWindowRepository windows, OfferingRepository offerings, MenuTimeRules time,
            OrderPricing pricing, InventoryService inventory, AuditService audit, OutboxWriter outbox,
            OrderEventPublisher publisher, OrderViews views, JdbcTemplate jdbc, EntityManager entityManager,
            Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.dates = dates;
        this.windows = windows;
        this.offerings = offerings;
        this.time = time;
        this.pricing = pricing;
        this.inventory = inventory;
        this.audit = audit;
        this.outbox = outbox;
        this.publisher = publisher;
        this.views = views;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // -- creation ---------------------------------------------------------

    @Transactional
    public Created create(OrderApi.CreateOrderRequest request, UUID customerId) {
        // Serialize per customer so two concurrent retries with the same key cannot both pass the
        // lookup below and race into the partial unique index.
        try {
            jdbc.queryForObject("SELECT id FROM customer WHERE id = ?::uuid FOR UPDATE", UUID.class,
                customerId.toString());
        } catch (EmptyResultDataAccessException ignored) {
            // customers.findById below reports a missing customer with the proper 401.
        }

        String idempotencyKey = trimToNull(request.idempotencyKey());
        if (idempotencyKey != null) {
            var existing = orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return new Created(views.order(existing.get()), false);
            }
        }

        Customer customer = requireOrderableCustomer(customerId);

        MenuDate date = dates.findById(request.menuDateId())
            .orElseThrow(() -> notFound("Menu date not found"));
        if (date.getStatus() != MenuDateStatus.PUBLISHED || date.getMenu().getStatus() != MenuStatus.PUBLISHED) {
            throw conflict("MENU_NOT_AVAILABLE", "Menu date is not published");
        }
        PickupWindow window = windows.findById(request.pickupWindowId())
            .orElseThrow(() -> notFound("Pickup window not found"));
        if (!window.isActive() || !window.getMenuDateId().equals(date.getId())) {
            throw conflict("WINDOW_NOT_AVAILABLE", "Pickup window is not available for this date");
        }
        Instant now = clock.instant();
        if (!now.isBefore(time.orderCutoffAt(window.getStartAt()))) {
            throw conflict("CUTOFF_PASSED", "Order cutoff has passed");
        }

        List<NormalizedLine> lines = normalize(request.items(), date.getId(), window.getId());
        reserveWindow(window.getId(), now);
        checkCustomerLimits(customerId, date.getId(), lines);
        reserveOfferings(lines, now);

        String orderNumber = nextOrderNumber(date.getBakeDate());
        UUID orderId = Uuids.v7();
        Instant expiresAt = time.orderCutoffAt(window.getStartAt());
        String notes = trimToNull(request.notes());
        insertOrder(orderId, orderNumber, customer, date, window, idempotencyKey, notes, expiresAt,
            totalOf(lines), now);
        for (NormalizedLine line : lines) {
            insertLine(orderId, line, now);
        }

        outbox.orderCreated(orderId, orderNumber, now);
        audit.record(ActorType.CUSTOMER, customerId, "ORDER_CREATED", "ORDER", orderId, null,
            Map.of("orderNumber", orderNumber, "status", OrderStatus.PENDING.name(),
                "totalCents", totalOf(lines)));

        Order created = orders.findById(orderId).orElseThrow();
        publisher.orderCreated(created);
        flushAndClear();
        return new Created(views.order(orders.findById(orderId).orElseThrow()), true);
    }

    private List<NormalizedLine> normalize(List<OrderApi.CreateOrderItem> items, UUID menuDateId, UUID windowId) {
        Map<LineKey, PendingLine> merged = new LinkedHashMap<>();
        for (OrderApi.CreateOrderItem item : items) {
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > 99) {
                throw new RequestValidationException("items.quantity", "must be between 1 and 99");
            }
            Offering offering = offerings.findById(item.offeringId())
                .orElseThrow(() -> conflict("OFFERING_NOT_AVAILABLE", "Offering not found"));
            if (!offering.isActive() || !offering.getMenuDateId().equals(menuDateId)) {
                throw conflict("OFFERING_NOT_AVAILABLE", "Offering is not available for this date");
            }
            if (offering.getWindows().stream().noneMatch(w -> w.getPickupWindowId().equals(windowId))) {
                throw conflict("OFFERING_NOT_AVAILABLE", "Offering is not assigned to this pickup window");
            }

            List<UUID> requestedOptionIds = item.optionIds() == null ? List.of() : item.optionIds();
            // Sorted distinct IDs are only the merge key. Reject duplicates here so they cannot be
            // discarded by a later merge before OrderPricing sees them.
            List<UUID> sortedOptionIds = new ArrayList<>(new LinkedHashSet<>(requestedOptionIds));
            if (sortedOptionIds.size() != requestedOptionIds.size()) {
                throw AuthException.badRequest("INVALID_CUSTOMIZATION", "Invalid customization: duplicate option id");
            }
            sortedOptionIds.sort(Comparator.comparing(UUID::toString));
            String notes = trimToNull(item.notes());
            LineKey key = new LineKey(offering.getId(), sortedOptionIds, notes);
            PendingLine previous = merged.get(key);
            if (previous == null) {
                merged.put(key, new PendingLine(offering, requestedOptionIds, notes, item.quantity()));
            } else {
                int quantity = previous.quantity() + item.quantity();
                if (quantity > 99) {
                    throw new RequestValidationException("items.quantity", "merged duplicate quantity exceeds 99");
                }
                merged.put(key, new PendingLine(previous.offering(), previous.optionIds(), notes, quantity));
            }
        }

        List<NormalizedLine> result = new ArrayList<>(merged.size());
        for (PendingLine pending : merged.values()) {
            OrderPricing.PricedLine priced = pricing.price(
                pending.offering(), pending.optionIds(), pending.quantity(), pending.notes());
            result.add(new NormalizedLine(pending.offering(), priced));
        }
        return result;
    }

    private void reserveWindow(UUID windowId, Instant now) {
        int updated = jdbc.update("""
            UPDATE pickup_window SET reserved_orders = reserved_orders + 1, updated_at = ?
            WHERE id = ?::uuid AND status = 'ACTIVE' AND reserved_orders < max_orders
            """, Timestamp.from(now), windowId.toString());
        if (updated == 0) {
            throw conflict("WINDOW_FULL", "Pickup window is full");
        }
    }

    private void checkCustomerLimits(UUID customerId, UUID menuDateId, List<NormalizedLine> lines) {
        for (Map.Entry<UUID, Integer> entry : quantitiesByOffering(lines).entrySet()) {
            UUID offeringId = entry.getKey();
            Offering offering = offerings.findById(offeringId).orElseThrow();
            Integer limit = offering.getMaxPerCustomer();
            if (limit == null) {
                continue;
            }
            Integer existing = jdbc.query("""
                SELECT ol.quantity FROM order_line ol
                JOIN orders o ON o.id = ol.order_id
                WHERE o.customer_id = ?::uuid AND ol.offering_id = ?::uuid AND o.menu_date_id = ?::uuid
                  AND o.status IN ('PENDING', 'ACCEPTED', 'READY')
                FOR UPDATE OF ol
                """, rs -> {
                    int total = 0;
                    while (rs.next()) {
                        total += rs.getInt("quantity");
                    }
                    return total;
                }, customerId.toString(), offeringId.toString(), menuDateId.toString());
            if (existing + entry.getValue() > limit) {
                throw conflict("CUSTOMER_LIMIT_EXCEEDED", "Per-customer quantity limit exceeded");
            }
        }
    }

    private void reserveOfferings(List<NormalizedLine> lines, Instant now) {
        for (Map.Entry<UUID, Integer> entry : quantitiesByOffering(lines).entrySet()) {
            UUID id = entry.getKey();
            int quantity = entry.getValue();
            int updated = jdbc.update("""
                UPDATE offering SET reserved_count = reserved_count + ?, updated_at = ?
                WHERE id = ?::uuid AND status = 'ACTIVE' AND reserved_count + ? <= quantity_limit
                """, quantity, Timestamp.from(now), id.toString(), quantity);
            if (updated == 0) {
                throw conflict("SOLD_OUT", "Offering sold out");
            }
        }
    }

    private String nextOrderNumber(LocalDate bakeDate) {
        Date sqlDate = Date.valueOf(bakeDate);
        jdbc.update("""
            INSERT INTO daily_order_sequence (bake_date, next_value) VALUES (?, 1)
            ON CONFLICT (bake_date) DO NOTHING
            """, sqlDate);
        int next = jdbc.queryForObject(
            "SELECT next_value FROM daily_order_sequence WHERE bake_date = ? FOR UPDATE", Integer.class, sqlDate);
        jdbc.update("UPDATE daily_order_sequence SET next_value = next_value + 1 WHERE bake_date = ?", sqlDate);
        return "BB-" + bakeDate.format(ORDER_DATE) + "-" + String.format("%04d", next);
    }

    private void insertOrder(UUID orderId, String orderNumber, Customer customer, MenuDate date,
            PickupWindow window, String idempotencyKey, String notes, Instant expiresAt, int total, Instant now) {
        jdbc.update("""
            INSERT INTO orders (id, order_number, customer_id, menu_date_id, pickup_window_id,
                idempotency_key, status, payment_status, subtotal_cents, total_cents, customer_name,
                customer_email, customer_phone, notes, expires_at, version, created_at, updated_at)
            VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, ?, 'PENDING', 'UNPAID', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """,
            orderId.toString(), orderNumber, customer.getId().toString(), date.getId().toString(),
            window.getId().toString(), idempotencyKey, total, total, customer.getName(),
            customer.getEmail(), customer.getPhoneE164(), notes, Timestamp.from(expiresAt),
            Timestamp.from(now), Timestamp.from(now));
    }

    private void insertLine(UUID orderId, NormalizedLine line, Instant now) {
        UUID lineId = Uuids.v7();
        OrderPricing.PricedLine priced = line.priced();
        jdbc.update("""
            INSERT INTO order_line (id, order_id, offering_id, item_name, unit_price_cents,
                quantity, line_total_cents, notes)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)
            """, lineId.toString(), orderId.toString(), priced.offeringId().toString(),
            priced.itemName(), priced.unitPriceCents(), priced.quantity(), priced.lineTotalCents(),
            priced.notes());
        for (OrderPricing.SelectedOption option : priced.selected()) {
            jdbc.update("""
                INSERT INTO order_line_option (id, order_line_id, group_name, option_name, price_delta_cents)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                """, Uuids.v7().toString(), lineId.toString(), option.groupName(), option.optionName(),
                option.priceDeltaCents());
        }
    }

    private static int totalOf(List<NormalizedLine> lines) {
        return lines.stream().mapToInt(line -> line.priced().lineTotalCents()).sum();
    }

    /** Per-offering quantities, iterated in ascending ID order so concurrent updates cannot deadlock. */
    private static Map<UUID, Integer> quantitiesByOffering(List<NormalizedLine> lines) {
        Map<UUID, Integer> byId = new LinkedHashMap<>();
        for (NormalizedLine line : lines) {
            byId.merge(line.offering().getId(), line.priced().quantity(), Integer::sum);
        }
        List<UUID> sortedIds = new ArrayList<>(byId.keySet());
        sortedIds.sort(Comparator.comparing(UUID::toString));
        Map<UUID, Integer> result = new LinkedHashMap<>();
        for (UUID id : sortedIds) {
            result.put(id, byId.get(id));
        }
        return result;
    }

    // -- reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderApi.Order getForCustomer(UUID orderId, UUID customerId) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
            .orElseThrow(() -> notFound("Order not found"));
        return views.order(order);
    }

    @Transactional(readOnly = true)
    public OrderApi.OrderList listForCustomer(UUID customerId, OrderApi.OrderFilter filter, int page, int size) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("customerId"), customerId));
            List<OrderStatus> statuses = statusesFor(filter);
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Order> result = orders.findAll(spec, pageRequest(page, size));
        return new OrderApi.OrderList(result.getContent().stream().map(views::order).toList(),
            result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderApi.Order getForAdmin(UUID orderId) {
        return views.order(orders.findById(orderId).orElseThrow(() -> notFound("Order not found")));
    }

    @Transactional(readOnly = true)
    public OrderApi.OrderList listForAdmin(LocalDate date, OrderStatus status, PaymentStatus paymentStatus,
            int page, int size) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (date != null) {
                predicates.add(cb.equal(root.get("menuDate").get("bakeDate"), date));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (paymentStatus != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Order> result = orders.findAll(spec, pageRequest(page, size));
        return new OrderApi.OrderList(result.getContent().stream().map(views::order).toList(),
            result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderApi.AdminSummary summary(LocalDate bakeDate) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
            SELECT o.status AS status, count(*) AS total
            FROM orders o JOIN menu_date d ON d.id = o.menu_date_id
            WHERE d.bake_date = ? GROUP BY o.status
            """, rs -> {
                counts.put(rs.getString("status"), rs.getLong("total"));
            }, Date.valueOf(bakeDate));
        long unpaid = jdbc.queryForObject("""
            SELECT count(*) FROM orders o JOIN menu_date d ON d.id = o.menu_date_id
            WHERE d.bake_date = ? AND o.payment_status = 'UNPAID'
              AND o.status IN ('PENDING', 'ACCEPTED', 'READY', 'COMPLETED')
            """, Long.class, Date.valueOf(bakeDate));
        return new OrderApi.AdminSummary(bakeDate,
            counts.getOrDefault("PENDING", 0L), counts.getOrDefault("ACCEPTED", 0L),
            counts.getOrDefault("READY", 0L), counts.getOrDefault("COMPLETED", 0L), unpaid);
    }

    @Transactional(readOnly = true)
    public OrderApi.BakeList bakeList(LocalDate bakeDate, Instant generatedAt) {
        Map<BakeItemKey, Integer> quantities = new TreeMap<>();
        Map<BakeOptionKey, Integer> optionCounts = new TreeMap<>();
        Set<UUID> orderIds = new LinkedHashSet<>();
        jdbc.query("""
            SELECT o.id AS order_id, ol.offering_id AS offering_id, ol.item_name AS item_name,
                   ol.quantity AS quantity, olo.group_name AS group_name, olo.option_name AS option_name
            FROM orders o
            JOIN menu_date d ON d.id = o.menu_date_id
            JOIN order_line ol ON ol.order_id = o.id
            LEFT JOIN order_line_option olo ON olo.order_line_id = ol.id
            WHERE d.bake_date = ? AND o.status IN ('ACCEPTED', 'READY', 'COMPLETED')
            ORDER BY ol.item_name ASC, ol.offering_id ASC, olo.group_name ASC, olo.option_name ASC
            """, rs -> {
                UUID orderId = rs.getObject("order_id", UUID.class);
                orderIds.add(orderId);
                UUID offeringId = rs.getObject("offering_id", UUID.class);
                String itemName = rs.getString("item_name");
                int quantity = rs.getInt("quantity");
                quantities.merge(new BakeItemKey(itemName, offeringId), quantity, Integer::sum);
                String groupName = rs.getString("group_name");
                String optionName = rs.getString("option_name");
                if (groupName != null && optionName != null) {
                    optionCounts.merge(new BakeOptionKey(offeringId, groupName, optionName), quantity, Integer::sum);
                }
            }, Date.valueOf(bakeDate));

        Map<UUID, List<OrderApi.BakeListOptionCount>> optionsByOffering = new LinkedHashMap<>();
        optionCounts.forEach((key, count) -> optionsByOffering.computeIfAbsent(key.offeringId(), ignored -> new ArrayList<>())
            .add(new OrderApi.BakeListOptionCount(key.groupName(), key.optionName(), count)));
        List<OrderApi.BakeListItem> items = new ArrayList<>();
        quantities.forEach((key, quantity) -> items.add(new OrderApi.BakeListItem(
            key.offeringId(), key.itemName(), quantity,
            optionsByOffering.getOrDefault(key.offeringId(), List.of()))));
        return new OrderApi.BakeList(bakeDate, generatedAt, orderIds.size(), items);
    }

    // -- lifecycle --------------------------------------------------------

    @Transactional
    public OrderApi.Order accept(UUID orderId, UUID adminId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw conflict("INVALID_STATE", "Only pending orders can be accepted");
        }
        Instant now = clock.instant();
        if (!now.isBefore(time.orderCutoffAt(order.getPickupWindow().getStartAt()))) {
            throw conflict("CUTOFF_PASSED", "Order cutoff has passed");
        }
        if (update("UPDATE orders SET status = 'ACCEPTED', accepted_at = ?, updated_at = ? "
            + "WHERE id = ?::uuid AND status = 'PENDING'", Timestamp.from(now), Timestamp.from(now), orderId) == 0) {
            throw conflict("INVALID_STATE", "Order is no longer pending");
        }
        audit.record(ActorType.ADMIN, adminId, "ORDER_ACCEPTED", "ORDER", orderId,
            Map.of("status", "PENDING"), Map.of("status", "ACCEPTED"));
        publisher.orderAccepted(refresh(orderId));
        return viewAfterWrite(orderId);
    }

    @Transactional
    public OrderApi.Order reject(UUID orderId, String reason, UUID adminId) {
        String trimmed = requireReason(reason);
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw conflict("INVALID_STATE", "Only pending orders can be rejected");
        }
        Instant now = clock.instant();
        if (update("""
            UPDATE orders SET status = 'REJECTED', rejection_reason = ?, updated_at = ?
            WHERE id = ?::uuid AND status = 'PENDING'
            """, trimmed, Timestamp.from(now), orderId) == 0) {
            throw conflict("INVALID_STATE", "Order is no longer pending");
        }
        inventory.release(orderId);
        audit.record(ActorType.ADMIN, adminId, "ORDER_REJECTED", "ORDER", orderId,
            Map.of("status", "PENDING"), Map.of("status", "REJECTED", "reason", trimmed));
        publisher.orderRejected(refresh(orderId), trimmed);
        return viewAfterWrite(orderId);
    }

    @Transactional
    public OrderApi.Order ready(UUID orderId, UUID adminId) {
        requireStatus(orderId, OrderStatus.ACCEPTED, "Only accepted orders can be marked ready");
        Instant now = clock.instant();
        if (update("UPDATE orders SET status = 'READY', ready_at = ?, updated_at = ? "
            + "WHERE id = ?::uuid AND status = 'ACCEPTED'", Timestamp.from(now), Timestamp.from(now), orderId) == 0) {
            throw conflict("INVALID_STATE", "Order is no longer accepted");
        }
        audit.record(ActorType.ADMIN, adminId, "ORDER_READY", "ORDER", orderId,
            Map.of("status", "ACCEPTED"), Map.of("status", "READY"));
        publisher.orderReady(refresh(orderId));
        return viewAfterWrite(orderId);
    }

    @Transactional
    public OrderApi.Order complete(UUID orderId, UUID adminId) {
        requireStatus(orderId, OrderStatus.READY, "Only ready orders can be completed");
        Instant now = clock.instant();
        if (update("UPDATE orders SET status = 'COMPLETED', completed_at = ?, updated_at = ? "
            + "WHERE id = ?::uuid AND status = 'READY'", Timestamp.from(now), Timestamp.from(now), orderId) == 0) {
            throw conflict("INVALID_STATE", "Order is no longer ready");
        }
        audit.record(ActorType.ADMIN, adminId, "ORDER_COMPLETED", "ORDER", orderId,
            Map.of("status", "READY"), Map.of("status", "COMPLETED"));
        publisher.orderCompleted(refresh(orderId));
        return viewAfterWrite(orderId);
    }

    @Transactional
    public OrderApi.Order cancelByCustomer(UUID orderId, UUID customerId, String reason) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
            .orElseThrow(() -> notFound("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.ACCEPTED) {
            throw conflict("INVALID_STATE", "Order cannot be cancelled in its current status");
        }
        Instant now = clock.instant();
        Instant bakeDayStart = order.getMenuDate().getBakeDate().atStartOfDay(time.zone()).toInstant();
        if (!now.isBefore(bakeDayStart)) {
            throw conflict("CANCELLATION_CLOSED", "Cancellation closes at bake day start");
        }
        String trimmed = trimToNull(reason);
        cancel(orderId, order.getStatus(), CancelledBy.CUSTOMER, trimmed, now);
        audit.record(ActorType.CUSTOMER, customerId, "ORDER_CANCELLED_BY_CUSTOMER", "ORDER", orderId,
            Map.of("status", order.getStatus().name()), cancellationDetail(trimmed));
        publisher.orderCancelled(refresh(orderId), trimmed, Actor.CUSTOMER);
        return viewAfterWrite(orderId);
    }

    @Transactional
    public OrderApi.Order cancelByAdmin(UUID orderId, String reason, UUID adminId) {
        String trimmed = requireReason(reason);
        Order order = findOrder(orderId);
        if (!order.getStatus().isActive()) {
            throw conflict("INVALID_STATE", "Only active orders can be cancelled");
        }
        cancel(orderId, order.getStatus(), CancelledBy.ADMIN, trimmed, clock.instant());
        audit.record(ActorType.ADMIN, adminId, "ORDER_CANCELLED_BY_ADMIN", "ORDER", orderId,
            Map.of("status", order.getStatus().name()), cancellationDetail(trimmed));
        publisher.orderCancelled(refresh(orderId), trimmed, Actor.ADMIN);
        return viewAfterWrite(orderId);
    }

    private void cancel(UUID orderId, OrderStatus from, CancelledBy cancelledBy, String reason, Instant now) {
        StringBuilder sql = new StringBuilder("UPDATE orders SET status = 'CANCELLED', cancelled_by = ?, updated_at = ?");
        List<Object> args = new ArrayList<>();
        args.add(cancelledBy.name());
        args.add(Timestamp.from(now));
        if (reason != null) {
            sql.append(", cancellation_reason = ?");
            args.add(reason);
        }
        sql.append(" WHERE id = ?::uuid AND status = ?");
        args.add(orderId.toString());
        args.add(from.name());
        if (update(sql.toString(), args.toArray()) == 0) {
            throw conflict("INVALID_STATE", "Order status changed concurrently");
        }
        inventory.release(orderId);
    }

    @Transactional
    public void expire(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        Instant now = clock.instant();
        int updated = jdbc.update("""
            UPDATE orders SET status = 'EXPIRED', cancelled_by = 'SYSTEM',
                cancellation_reason = 'Cutoff passed', updated_at = ?
            WHERE id = ?::uuid AND status = 'PENDING' AND expires_at <= ?
            """, Timestamp.from(now), orderId.toString(), Timestamp.from(now));
        if (updated == 0) {
            return;
        }
        inventory.release(orderId);
        audit.record(ActorType.SYSTEM, null, "ORDER_EXPIRED", "ORDER", orderId,
            Map.of("status", "PENDING"), Map.of("status", "EXPIRED", "at", now.toString()));
        publisher.orderExpired(refresh(orderId));
        flushAndClear();
    }

    @Transactional
    public void cancelForMenuDate(UUID menuDateId) {
        MenuDate date = dates.findById(menuDateId).orElseThrow();
        String reason = trimToNull(date.getCancellationReason());
        String detail = reason != null ? reason : "Menu date cancelled";
        Instant now = clock.instant();
        List<Order> active = orders.findAllByMenuDate_IdAndStatusIn(menuDateId, ACTIVE);
        for (Order order : active) {
            int updated = jdbc.update("""
                UPDATE orders SET status = 'CANCELLED', cancelled_by = 'SYSTEM',
                    cancellation_reason = ?, updated_at = ?
                WHERE id = ?::uuid AND status IN ('PENDING', 'ACCEPTED', 'READY')
                """, detail, Timestamp.from(now), order.getId().toString());
            if (updated == 0) {
                continue;
            }
            inventory.release(order.getId());
            audit.record(ActorType.SYSTEM, null, "ORDER_CANCELLED_BY_ADMIN", "ORDER", order.getId(),
                Map.of("status", order.getStatus().name()), Map.of("status", "CANCELLED", "reason", detail));
            publisher.menuDateCancelled(date, detail);
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredPendingOrderIds(Instant now) {
        return jdbc.query("""
            SELECT id FROM orders WHERE status = 'PENDING' AND expires_at <= ? ORDER BY id
            """, (ResultSetExtractor<List<UUID>>) rs -> {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject("id", UUID.class));
                }
                return ids;
            }, Timestamp.from(now));
    }

    // -- helpers ----------------------------------------------------------

    private Customer requireOrderableCustomer(UUID customerId) {
        Customer customer = customers.findById(customerId)
            .orElseThrow(() -> AuthException.unauthorized("Customer not found"));
        if (customer.getName() == null || customer.getName().isBlank()) {
            throw new RequestValidationException("name", "must be set before ordering");
        }
        if (!customer.isEmailVerified() && !customer.isPhoneVerified()) {
            throw new RequestValidationException("contact", "at least one verified contact channel is required");
        }
        return customer;
    }

    private Order findOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> notFound("Order not found"));
    }

    private void requireStatus(UUID orderId, OrderStatus expected, String detail) {
        if (findOrder(orderId).getStatus() != expected) {
            throw conflict("INVALID_STATE", detail);
        }
    }

    private int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** Flush JPA writes (audits, cascades) before detaching so clear() cannot discard them. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Order refresh(UUID orderId) {
        flushAndClear();
        return orders.findById(orderId).orElseThrow();
    }

    private OrderApi.Order viewAfterWrite(UUID orderId) {
        return views.order(refresh(orderId));
    }

    private static PageRequest pageRequest(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(1, size));
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private static List<OrderStatus> statusesFor(OrderApi.OrderFilter filter) {
        if (filter == null || filter == OrderApi.OrderFilter.ALL) {
            return List.of();
        }
        return switch (filter) {
            case ACTIVE -> ACTIVE;
            case COMPLETED -> List.of(OrderStatus.COMPLETED);
            case CANCELLED -> TERMINAL_FAILURES;
            case ALL -> List.of();
        };
    }

    private static String requireReason(String reason) {
        String trimmed = trimToNull(reason);
        if (trimmed == null) {
            throw new RequestValidationException("reason", "must not be blank");
        }
        return trimmed;
    }

    private static Map<String, Object> cancellationDetail(String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", "CANCELLED");
        if (reason != null) {
            detail.put("reason", reason);
        }
        return detail;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static AuthException conflict(String code, String detail) {
        return new AuthException(HttpStatus.CONFLICT, code, detail);
    }

    private static AuthException notFound(String detail) {
        return new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", detail);
    }

    private record LineKey(UUID offeringId, List<UUID> optionIds, String notes) {}

    private record PendingLine(Offering offering, List<UUID> optionIds, String notes, int quantity) {}

    private record NormalizedLine(Offering offering, OrderPricing.PricedLine priced) {}

    private record BakeItemKey(String itemName, UUID offeringId)
            implements Comparable<BakeItemKey> {
        @Override
        public int compareTo(BakeItemKey other) {
            int byName = itemName.compareTo(other.itemName);
            return byName != 0 ? byName : offeringId.compareTo(other.offeringId);
        }
    }

    private record BakeOptionKey(UUID offeringId, String groupName, String optionName)
            implements Comparable<BakeOptionKey> {
        @Override
        public int compareTo(BakeOptionKey other) {
            int byOffering = offeringId.compareTo(other.offeringId);
            if (byOffering != 0) return byOffering;
            int byGroup = groupName.compareTo(other.groupName);
            return byGroup != 0 ? byGroup : optionName.compareTo(other.optionName);
        }
    }

    public record Created(OrderApi.Order order, boolean created) {}
}
