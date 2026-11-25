package com.bethanysbakery.api.auth.otp;

/** Source of one-time codes; swap only under the non-production dummy profile. */
public interface OtpCodeGenerator {

    String generate();
}
