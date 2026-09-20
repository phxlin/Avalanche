package com.avalanche.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.avalanche.app.ui.components.LocalMoney
import com.avalanche.app.ui.dashboard.DashboardScreen
import com.avalanche.app.ui.debts.DebtDetailScreen
import com.avalanche.app.ui.debts.DebtListScreen
import com.avalanche.app.ui.debts.EditDebtScreen
import com.avalanche.app.ui.payments.LogPaymentScreen
import com.avalanche.app.ui.payments.decodeAllocations
import com.avalanche.app.ui.payments.encodeAllocations
import com.avalanche.app.ui.plan.PlanScreen
import com.avalanche.app.ui.settings.SettingsScreen
import com.avalanche.app.util.MoneyFormatter
import kotlinx.coroutines.launch

private object Routes {
    /** Home, Debts, Plan and Settings: one destination holding a swipeable pager of the four tabs. */
    const val MAIN = "main"
    const val DETAIL = "debt/{debtId}"
    const val EDIT = "edit?debtId={debtId}"
    const val LOG = "log?debtId={debtId}&alloc={alloc}"
}

private data class Tab(val label: String, val icon: ImageVector)

private const val TAB_HOME = 0
private const val TAB_DEBTS = 1
private const val TAB_PLAN = 2
private const val TAB_SETTINGS = 3

private val tabs = listOf(
    Tab("Home", Icons.Filled.Home),
    Tab("Debts", Icons.Filled.CreditCard),
    Tab("Plan", Icons.AutoMirrored.Filled.TrendingDown),
    Tab("Settings", Icons.Filled.Settings),
)

@Composable
fun AvalancheApp() {
    val container = rememberContainer()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val money = remember(settings.currency) { MoneyFormatter(settings.currency) }
    CompositionLocalProvider(LocalMoney provides money) { AppNav() }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        NavHost(nav, startDestination = Routes.MAIN, modifier = Modifier.padding(inner).consumeWindowInsets(inner)) {
            composable(Routes.MAIN) { MainTabs(nav) }
            composable(Routes.DETAIL, arguments = listOf(navArgument("debtId") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("debtId") ?: return@composable
                DebtDetailScreen(
                    debtId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("edit?debtId=$id") },
                    onLogPayment = { nav.navigate("log?debtId=$id") },
                )
            }
            composable(Routes.EDIT, arguments = listOf(navArgument("debtId") { type = NavType.LongType; defaultValue = -1L })) { e ->
                val id = e.arguments?.getLong("debtId")?.takeIf { it >= 0 }
                EditDebtScreen(debtId = id, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.LOG,
                arguments = listOf(
                    navArgument("debtId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("alloc") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                LogPaymentScreen(
                    prefillDebtId = e.arguments?.getLong("debtId")?.takeIf { it >= 0 },
                    prefillAllocations = decodeAllocations(e.arguments?.getString("alloc").orEmpty()),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

/** The four main screens side by side: swipe between them, or tap the bottom bar. The bar follows the swipe. */
@Composable
private fun MainTabs(nav: NavHostController) {
    // Saved with the destination, so coming back from a debt's detail screen lands on the same tab.
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    fun goTo(page: Int) {
        scope.launch { pager.animateScrollToPage(page) }
    }

    // System back from another tab returns to Home first; from Home it leaves the app.
    BackHandler(enabled = pager.currentPage != TAB_HOME) { goTo(TAB_HOME) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        onClick = { goTo(index) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        HorizontalPager(pager, Modifier.padding(inner).consumeWindowInsets(inner)) { page ->
            when (page) {
                TAB_HOME -> DashboardScreen(
                    onAddDebt = { nav.navigate("edit") },
                    onLogPayment = { nav.navigate("log") },
                    onOpenPlan = { goTo(TAB_PLAN) },
                )
                TAB_DEBTS -> DebtListScreen(onAddDebt = { nav.navigate("edit") }, onOpenDebt = { nav.navigate("debt/$it") })
                TAB_PLAN -> PlanScreen(
                    onAddDebt = { nav.navigate("edit") },
                    onLogLumpSum = { alloc -> nav.navigate("log?alloc=${Uri.encode(encodeAllocations(alloc))}") },
                )
                TAB_SETTINGS -> SettingsScreen()
            }
        }
    }
}
