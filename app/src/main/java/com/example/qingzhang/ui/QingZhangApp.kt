package com.example.qingzhang.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.qingzhang.data.BillFilter
import com.example.qingzhang.data.BudgetWarning
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.startDate
import com.example.qingzhang.notifications.NoticeKind
import com.example.qingzhang.notifications.NoticeTarget
import com.example.qingzhang.notifications.ReminderPreferences
import com.example.qingzhang.ui.components.BudgetWarningsDialog
import com.example.qingzhang.ui.components.UpdateDialogs
import com.example.qingzhang.ui.screens.BillScreen
import com.example.qingzhang.ui.screens.CategoriesScreen
import com.example.qingzhang.ui.screens.DailyLimitScreen
import com.example.qingzhang.ui.screens.EditTransactionScreen
import com.example.qingzhang.ui.screens.HomeScreen
import com.example.qingzhang.ui.screens.ImportScreen
import com.example.qingzhang.ui.screens.LivingPlansScreen
import com.example.qingzhang.ui.screens.MineScreen
import com.example.qingzhang.ui.screens.ReminderSettingsScreen
import com.example.qingzhang.ui.screens.StatsScreen
import com.example.qingzhang.ui.screens.SavingsScreen
import com.example.qingzhang.ui.screens.UpdateScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class MainDestination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    MainDestination("home", "首页", Icons.Default.Home),
    MainDestination("bills", "账单", Icons.AutoMirrored.Filled.ReceiptLong),
    MainDestination("stats", "统计", Icons.Default.BarChart),
    MainDestination("mine", "我的", Icons.Default.Person),
)

private const val NAV_ANIMATION_MS = 280

private fun navigationDirection(fromRoute: String?, toRoute: String?): Int {
    val from = destinations.indexOfFirst { it.route == fromRoute }
    val to = destinations.indexOfFirst { it.route == toRoute }
    return if (from >= 0 && to >= 0 && to < from) -1 else 1
}

@Composable
internal fun QingZhangApp(
    ledgerViewModel: LedgerViewModel,
    importViewModel: ImportViewModel,
    updateViewModel: UpdateViewModel,
    reminderPreferences: ReminderPreferences,
    reminderTarget: NoticeTarget?,
    onReminderOpened: () -> Unit,
    onReminderPermissionChanged: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var budgetWarnings by remember { mutableStateOf<List<BudgetWarning>>(emptyList()) }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(ledgerViewModel) {
        ledgerViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(importViewModel) {
        importViewModel.events.collect { event ->
            when (event) {
                is ImportEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is ImportEvent.Warnings -> budgetWarnings = event.values
            }
        }
    }
    LaunchedEffect(updateViewModel) {
        updateViewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(reminderTarget) {
        val target = reminderTarget ?: return@LaunchedEffect
        val route = when (target.kind) {
            NoticeKind.DAILY -> "edit"
            NoticeKind.OVER_BUDGET -> {
                val plan = ledgerViewModel.livingPlans.first().firstOrNull { it.plan.id == target.id }?.plan
                if (plan != null) {
                    ledgerViewModel.updateBillFilter { BillFilter(plan.startDate, plan.endDate, TransactionType.EXPENSE) }
                }
                "bills"
            }
            NoticeKind.SETTLEMENT -> "living-plans?planId=${target.id}"
            NoticeKind.GOAL -> "savings?goalId=${target.id}"
        }
        lifecycle.withResumed {
            navController.navigate(route)
            onReminderOpened()
        }
    }

    fun navigateMain(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openEdit(id: Long) = navController.navigate("edit?transactionId=$id")

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (destinations.any { it.route == currentRoute }) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navigateMain(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == "home") {
                FloatingActionButton(onClick = { navController.navigate("edit") }) {
                    Icon(Icons.Default.Add, contentDescription = "记一笔")
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = navigationDirection(initialState.destination.route, targetState.destination.route)
                slideInHorizontally(
                    animationSpec = tween(NAV_ANIMATION_MS, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> direction * fullWidth },
                ) + fadeIn(animationSpec = tween(NAV_ANIMATION_MS))
            },
            exitTransition = {
                val direction = navigationDirection(initialState.destination.route, targetState.destination.route)
                slideOutHorizontally(
                    animationSpec = tween(NAV_ANIMATION_MS, easing = FastOutSlowInEasing),
                    targetOffsetX = { fullWidth -> -direction * fullWidth / 3 },
                ) + fadeOut(animationSpec = tween(NAV_ANIMATION_MS))
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = tween(NAV_ANIMATION_MS, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> -fullWidth },
                ) + fadeIn(animationSpec = tween(NAV_ANIMATION_MS))
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(NAV_ANIMATION_MS, easing = FastOutSlowInEasing),
                    targetOffsetX = { fullWidth -> fullWidth },
                ) + fadeOut(animationSpec = tween(NAV_ANIMATION_MS))
            },
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = ledgerViewModel,
                    onAdd = { navController.navigate("edit") },
                    onImport = { navController.navigate("import") },
                    onStats = { navigateMain("stats") },
                    onBills = { navigateMain("bills") },
                    onEdit = ::openEdit,
                    onLimit = { navController.navigate("limit") },
                    onLivingPlans = { navController.navigate("living-plans") },
                )
            }
            composable("bills") {
                BillScreen(
                    viewModel = ledgerViewModel,
                    onEdit = ::openEdit,
                    onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )
            }
            composable("stats") { StatsScreen(ledgerViewModel, ::openEdit) }
            composable("mine") {
                MineScreen(
                    viewModel = ledgerViewModel,
                    onImport = { navController.navigate("import") },
                    onCategories = { navController.navigate("categories") },
                    onLimit = { navController.navigate("limit") },
                    onUpdate = { navController.navigate("update") },
                    onLivingPlans = { navController.navigate("living-plans") },
                    onSavings = { navController.navigate("savings") },
                    onReminders = { navController.navigate("reminders") },
                )
            }
            composable(
                route = "edit?transactionId={transactionId}",
                arguments = listOf(navArgument("transactionId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                val id = entry.arguments?.getLong("transactionId") ?: -1L
                EditTransactionScreen(
                    transactionId = id.takeIf { it > 0 },
                    viewModel = ledgerViewModel,
                    onBack = { navController.popBackStack() },
                    onWarning = { budgetWarnings = it },
                )
            }
            composable("import") { ImportScreen(importViewModel, ledgerViewModel) { navController.popBackStack() } }
            composable("categories") { CategoriesScreen(ledgerViewModel) { navController.popBackStack() } }
            composable("limit") { DailyLimitScreen(ledgerViewModel) { navController.popBackStack() } }
            composable(
                "living-plans?planId={planId}",
                arguments = listOf(navArgument("planId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                LivingPlansScreen(
                    ledgerViewModel,
                    onBack = { navController.popBackStack() },
                    onSavings = { navController.navigate("savings") },
                    initialPlanId = entry.arguments?.getLong("planId")?.takeIf { it > 0 },
                )
            }
            composable(
                "savings?goalId={goalId}",
                arguments = listOf(navArgument("goalId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                SavingsScreen(
                    ledgerViewModel,
                    onBack = { navController.popBackStack() },
                    initialGoalId = entry.arguments?.getLong("goalId")?.takeIf { it > 0 },
                )
            }
            composable("reminders") {
                ReminderSettingsScreen(reminderPreferences, onReminderPermissionChanged) { navController.popBackStack() }
            }
            composable("update") { UpdateScreen(updateViewModel) { navController.popBackStack() } }
        }
    }

    BudgetWarningsDialog(warnings = budgetWarnings, onDismiss = { budgetWarnings = emptyList() })
    UpdateDialogs(updateViewModel, updateState)
}
