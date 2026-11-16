package com.bethanysbakery.api.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bakery")
public record BakeryProperties(
    @NotBlank(message = "BAKERY_NAME must not be blank") String name,
    @NotBlank(message = "BAKERY_TIMEZONE must not be blank") String timezone,
    @NotBlank(message = "BAKERY_PICKUP_ADDRESS must not be blank") String pickupAddress,
    @NotBlank(message = "BAKERY_MAPS_URL must not be blank") String mapsUrl,
    @NotBlank(message = "BAKERY_SUPPORT_EMAIL must not be blank")
        @Email(message = "BAKERY_SUPPORT_EMAIL must be valid") String supportEmail,
    @Min(value = 0, message = "ORDER_CUTOFF_HOURS must be >= 0") Integer orderCutoffHours
) {}
