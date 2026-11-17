package com.bethanysbakery.api.auth.customer;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.domain.auth.Customer;

public final class CustomerMapper {
    private CustomerMapper() {}

    public static AuthApi.Customer toApi(Customer customer) {
        return new AuthApi.Customer(
            customer.getId(),
            customer.getName(),
            customer.getEmail(),
            customer.getPhoneE164(),
            customer.isEmailVerified(),
            customer.isPhoneVerified(),
            customer.hasSmsConsent(),
            customer.getCreatedAt()
        );
    }
}
