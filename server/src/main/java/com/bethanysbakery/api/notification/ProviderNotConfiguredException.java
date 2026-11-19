package com.bethanysbakery.api.notification;

/** Raised when a provider credential is missing outside the local profile. */
public class ProviderNotConfiguredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProviderNotConfiguredException(String provider) {
        super(provider + " is not configured");
    }
}
