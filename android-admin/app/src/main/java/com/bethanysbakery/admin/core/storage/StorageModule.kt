package com.bethanysbakery.admin.core.storage

import android.content.Context
import com.bethanysbakery.admin.core.security.DeviceKeyManager
import com.bethanysbakery.admin.core.security.EncryptedFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSecrets(@ApplicationContext context: Context): EncryptedFileStore = EncryptedFileStore(context)

    @Provides
    @Singleton
    fun provideDeviceKeys(secrets: EncryptedFileStore): DeviceKeyManager = DeviceKeyManager(secrets)

    @Provides
    @Singleton
    fun providePairedDeviceStore(
        @ApplicationContext context: Context,
        secrets: EncryptedFileStore,
    ): PairedDeviceStore = PairedDeviceStore(context, secrets)

    @Provides
    @Singleton
    fun provideTokenStore(secrets: EncryptedFileStore): TokenStore = TokenStore(secrets)
}
