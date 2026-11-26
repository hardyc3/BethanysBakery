package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.network.DeviceApiKeyProvider
import com.bethanysbakery.admin.core.security.DeviceKeyManager
import com.bethanysbakery.admin.core.storage.PairedDeviceStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnpairUseCase @Inject constructor(
    private val store: PairedDeviceStore,
    private val keys: DeviceKeyManager,
    private val apiKey: DeviceApiKeyProvider,
) {
    suspend operator fun invoke() {
        store.clear()
        keys.delete()
        apiKey.clear()
    }
}
