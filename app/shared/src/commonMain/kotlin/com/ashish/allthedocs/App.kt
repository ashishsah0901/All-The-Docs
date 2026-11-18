package com.ashish.allthedocs

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.ui.cloud.CloudSearchScreen
import com.ashish.allthedocs.ui.scenario.NewScenarioScreen
import com.ashish.allthedocs.ui.scenario.ScenarioDetailScreen
import com.ashish.allthedocs.ui.scenario.ScenarioListScreen
import com.ashish.allthedocs.ui.settings.SettingsScreen
import com.ashish.allthedocs.ui.vault.AddDocumentScreen
import com.ashish.allthedocs.ui.vault.VaultDocumentScreen
import com.ashish.allthedocs.ui.vault.VaultListScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data object ScenariosRoute

@Serializable
data object VaultRoute

@Serializable
data object SettingsRoute

@Serializable
data class NewScenarioRoute(val draftId: String, val curatedScenarioId: String = "") {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun start(curatedScenarioId: String = "") = NewScenarioRoute(Uuid.random().toString(), curatedScenarioId)
    }
}

@Serializable
data class ScenarioDetailRoute(val id: String)

@Serializable
data class VaultDocumentRoute(val id: String)

@Serializable
data class AddDocumentRoute(
    val expectedTypeKeys: String = "",
    val suggestedTitle: String = "",
    val incoming: Boolean = false,
) {
    val expectedTypes: List<DocumentType>
        get() = expectedTypeKeys.split(',').filter { it.isNotBlank() }.map { DocumentType.fromKey(it) }

    companion object {
        fun of(types: List<DocumentType>, title: String) =
            AddDocumentRoute(types.joinToString(",") { it.key }, title)
    }
}

@Serializable
data class CloudSearchRoute(val expectedTypeKeys: String = "", val suggestedTitle: String = "") {
    val expectedTypes: List<DocumentType>
        get() = expectedTypeKeys.split(',').filter { it.isNotBlank() }.map { DocumentType.fromKey(it) }

    companion object {
        fun of(types: List<DocumentType>, title: String) = CloudSearchRoute(types.joinToString(",") { it.key }, title)
    }
}

private data class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val topLevelDestinations = listOf(
    TopLevelDestination(ScenariosRoute, ScenariosRoute::class, "Scenarios", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(VaultRoute, VaultRoute::class, "Vault", Icons.Default.Lock),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, "Settings", Icons.Default.Settings),
)

@Composable
fun App() {
    MaterialTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        val showBottomBar = topLevelDestinations.any { destination?.hasRoute(it.routeClass) == true }
        val incomingDocuments = koinInject<IncomingDocuments>()
        val pendingIncoming by incomingDocuments.pending.collectAsStateWithLifecycle()

        LaunchedEffect(pendingIncoming != null) {
            if (pendingIncoming != null) navController.navigate(AddDocumentRoute(incoming = true))
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        topLevelDestinations.forEach { item ->
                            NavigationBarItem(
                                selected = destination?.hasRoute(item.routeClass) == true,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = ScenariosRoute,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable<ScenariosRoute> {
                    ScenarioListScreen(
                        onNewScenario = { navController.navigate(NewScenarioRoute.start()) },
                        onQuickStart = { navController.navigate(NewScenarioRoute.start(it)) },
                        onContinueDraft = { navController.navigate(NewScenarioRoute(it)) },
                        onOpenScenario = { navController.navigate(ScenarioDetailRoute(it)) },
                    )
                }
                composable<NewScenarioRoute> { entry ->
                    val route = entry.toRoute<NewScenarioRoute>()
                    NewScenarioScreen(
                        draftId = route.draftId,
                        curatedScenarioId = route.curatedScenarioId,
                        onBack = { navController.popBackStack() },
                        onCreated = { id ->
                            navController.navigate(ScenarioDetailRoute(id)) {
                                popUpTo<NewScenarioRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<ScenarioDetailRoute> { entry ->
                    ScenarioDetailScreen(
                        scenarioId = entry.toRoute<ScenarioDetailRoute>().id,
                        onBack = { navController.popBackStack() },
                        onAddDocument = { types, title -> navController.navigate(AddDocumentRoute.of(types, title)) },
                        onFindInAccounts = { types, title -> navController.navigate(CloudSearchRoute.of(types, title)) },
                        onOpenDocument = { navController.navigate(VaultDocumentRoute(it)) },
                    )
                }
                composable<VaultRoute> {
                    VaultListScreen(
                        onAddDocument = { navController.navigate(AddDocumentRoute()) },
                        onOpenDocument = { navController.navigate(VaultDocumentRoute(it)) },
                    )
                }
                composable<VaultDocumentRoute> { entry ->
                    VaultDocumentScreen(
                        documentId = entry.toRoute<VaultDocumentRoute>().id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<AddDocumentRoute> { entry ->
                    val route = entry.toRoute<AddDocumentRoute>()
                    AddDocumentScreen(
                        expectedTypes = route.expectedTypes,
                        suggestedTitle = route.suggestedTitle,
                        incoming = route.incoming,
                        onBack = { navController.popBackStack() },
                        onFindInAccounts = {
                            navController.navigate(CloudSearchRoute(route.expectedTypeKeys, route.suggestedTitle)) {
                                popUpTo<AddDocumentRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<CloudSearchRoute> { entry ->
                    val route = entry.toRoute<CloudSearchRoute>()
                    CloudSearchScreen(
                        expectedTypes = route.expectedTypes,
                        onBack = { navController.popBackStack() },
                        onReview = {
                            navController.navigate(AddDocumentRoute(route.expectedTypeKeys, route.suggestedTitle, incoming = true)) {
                                popUpTo<CloudSearchRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen()
                }
            }
        }
    }
}
