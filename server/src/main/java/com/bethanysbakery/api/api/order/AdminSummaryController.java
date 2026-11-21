package com.bethanysbakery.api.api.order;

import com.bethanysbakery.api.order.OrderService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminSummaryController {

    private final OrderService service;
    private final Clock clock;

    public AdminSummaryController(OrderService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public OrderApi.AdminSummary summary(@RequestParam LocalDate date) {
        return service.summary(date);
    }

    @GetMapping("/bake-list")
    public OrderApi.BakeList bakeList(@RequestParam LocalDate date) {
        return service.bakeList(date, clock.instant());
    }
}
