package com.bethanysbakery.api.api.publicapi;

import com.bethanysbakery.api.config.BakeryProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublicConfigController {

    private final BakeryProperties props;

    public PublicConfigController(BakeryProperties props) {
        this.props = props;
    }

    @GetMapping("/config")
    public PublicConfigResponse config() {
        return new PublicConfigResponse(
            props.name(),
            props.timezone(),
            props.pickupAddress(),
            props.mapsUrl(),
            props.supportEmail(),
            props.orderCutoffHours()
        );
    }

    public record PublicConfigResponse(
        String bakeryName,
        String timezone,
        String pickupAddress,
        String mapsUrl,
        String supportEmail,
        Integer orderCutoffHours
    ) {}
}
