package com.avalanche.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.ui.components.DebtCard
import com.avalanche.app.ui.components.EmptyState
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.appViewModel

@Composable
fun DebtListScreen(onAddDebt: () -> Unit, onOpenDebt: (Long) -> Unit) {
    val vm: DebtListViewModel = appViewModel { DebtListViewModel(it.repository, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = "Debts",
        floatingActionButton = {
            if (state.items.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = onAddDebt) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add debt")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.CreditCard,
                    title = "No debts yet",
                    body = "Add a credit card, loan or any balance you're paying down.",
                    actionLabel = "Add your first debt",
                    onAction = onAddDebt,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.id }) { debt ->
                        DebtCard(debt, onClick = { onOpenDebt(debt.id) }, focus = debt.id == state.focusId, paidThisMonth = debt.id in state.paidThisMonth)
                    }
                }
            }
        }
    }
}
