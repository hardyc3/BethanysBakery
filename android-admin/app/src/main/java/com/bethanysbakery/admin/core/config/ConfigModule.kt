package com.bethanysbakery.admin.core.config

import com.bethanysbakery.admin.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun provideConfig(): AdminBuildConfig = AdminBuildConfig(
        pairUrl = BuildConfig.APP_PAIR_URL,
        adminApiUrl = BuildConfig.APP_ADMIN_API_URL,
        pins = BuildConfig.ADMIN_API_PINS.splitToPins(),
        pinningEnabled = BuildConfig.PINNING_ENABLED,
        isDebug = BuildConfig.DEBUG,
        debugLanHosts = BuildConfig.DEBUG_LAN_HOSTS.splitToList(),
        appVersion = BuildConfig.VERSION_NAME,
        testCaPem = if (BuildConfig.DEBUG) BuildConfig.TEST_CA_PEM else "",
    )

    private fun String.splitToPins(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun String.splitToList(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
