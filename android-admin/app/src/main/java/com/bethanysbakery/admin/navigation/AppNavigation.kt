package com.bethanysbakery.admin.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bethanysbakery.admin.feature.login.LoginPlaceholderScreen
import com.bethanysbakery.admin.feature.pairing.PairingScreen

private object Routes {
    const val PAIRING = "pairing"
    const val POST_PAIRING = "post_pairing/{deviceId}/{certificateExpiresAt}"

    fun postPairing(deviceId: String, certificateExpiresAt: Long): String =
        "post_pairing/$deviceId/$certificateExpiresAt"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PAIRING) {
        composable(Routes.PAIRING) {
            PairingScreen(
                onPaired = { deviceId, expiry ->
                    navController.navigate(Routes.postPairing(deviceId, expiry)) {
                        popUpTo(Routes.PAIRING) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.POST_PAIRING,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType },
                navArgument("certificateExpiresAt") { type = NavType.LongType },
            ),
        ) { entry ->
            LoginPlaceholderScreen(
                deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                certificateExpiresAt = entry.arguments?.getLong("certificateExpiresAt") ?: 0L,
                onUnpaired = {
                    navController.navigate(Routes.PAIRING) {
                        popUpTo(Routes.POST_PAIRING) { inclusive = true }
                    }
                },
            )
        }
    }
}
