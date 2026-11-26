package com.bethanysbakery.admin.core.security

data class KeyMaterial(
    val publicKeyPem: String,
    val csrPem: String,
)
