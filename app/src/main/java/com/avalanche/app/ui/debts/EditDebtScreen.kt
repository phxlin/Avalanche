package com.avalanche.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.avalanche.app.data.DebtType
import com.avalanche.app.ui.components.DecimalField
import com.avalanche.app.ui.components.LoadingBox
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.icon
import com.avalanche.app.ui.components.money
import com.avalanche.app.util.formatDuration
import com.avalanche.app.ui.appViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDebtScreen(debtId: Long?, onDone: () -> Unit, onBack: () -> Unit) {
    val vm: EditDebtViewModel = appViewModel(key = "edit-${debtId ?: "new"}") { EditDebtViewModel(it.repository, debtId) }
    val showErrors = vm.showErrors

    ScreenScaffold(title = if (vm.isEditing) "Edit debt" else "Add a debt", onBack = onBack) { padding ->
        if (vm.loading) {
            LoadingBox(padding)
            return@ScreenScaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Visa, Car loan") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = showErrors && vm.nameError != null,
                supportingText = if (showErrors) vm.nameError?.let { { Text(it) } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebtType.entries.forEach { t ->
                        FilterChip(
                            selected = vm.type == t,
                            onClick = { vm.type = t },
                            label = { Text(t.label) },
                            leadingIcon = { Icon(t.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                Text(
                    if (vm.type.revolving) "Revolving: interest applies to a changing balance. Avalanche puts it first when the APR is highest."
                    else "Installment: a fixed loan that follows a set payoff schedule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DecimalField(
                value = vm.balance,
                onValueChange = { vm.balance = it },
                label = "Current balance",
                error = if (showErrors) vm.balanceError else null,
                supporting = if (vm.editingWithHistory) "Changing this records a balance adjustment in the history." else null,
            )
            val aprSettlementText = vm.aprChangeSettlement?.let {
                "Changing the rate adds the interest accrued so far at the old rate (about ${money(it)}) to the balance."
            }
            val originalText = vm.impliedOriginal?.let { money(it) }
            val preview = vm.interestPreview
            val estimate = preview?.estimate
            val estimateText = when {
                preview == null -> null
                estimate == null -> "Interest already paid can't be estimated: at that starting balance the minimum payment wouldn't cover the interest."
                else -> "Estimated interest already paid: about ${money(estimate.interest)}, assuming about ${formatDuration(Math.round(estimate.months).toInt())} of minimum payments."
            }
            DecimalField(
                value = vm.percentPaid,
                onValueChange = { vm.percentPaid = it },
                label = "Already paid off (optional)",
                suffix = "%",
                maxDecimals = 2,
                error = vm.percentPaidError,
                supporting = if (originalText == null) {
                    "Already paid part of this down? Enter how much (up to two decimals, 30 means 30%) and progress starts there instead of 0%."
                } else {
                    listOfNotNull("So the original balance was about $originalText.", estimateText).joinToString("\n")
                },
            )
            DecimalField(
                value = vm.apr,
                onValueChange = { vm.apr = it },
                label = "APR",
                suffix = "%",
                maxDecimals = EditDebtViewModel.APR_DECIMALS,
                error = if (showErrors) vm.aprError else null,
                supporting = aprSettlementText ?: "Annual interest rate, from your statement.",
            )
            DecimalField(
                value = vm.minPayment,
                onValueChange = { vm.minPayment = it },
                label = "Minimum monthly payment",
                error = if (showErrors) vm.minPaymentError else null,
            )

            // Lets the payoff schedule know this month's payment is already made, so it doesn't count it a second time.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = vm.paidThisMonth,
                        enabled = !vm.coveredByLoggedPayments,
                        role = Role.Checkbox,
                        onValueChange = { vm.paidThisMonth = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = vm.paidThisMonth, onCheckedChange = null, enabled = !vm.coveredByLoggedPayments)
                Column {
                    Text("This month's payment is already made", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (vm.coveredByLoggedPayments) {
                            "Covered by the payments you've logged this month."
                        } else {
                            "Tick this if the balance above is after this month's payment. The schedule then starts paying this debt next month."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            vm.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { vm.save(onDone) }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.isEditing) "Save changes" else "Add debt")
            }
        }
    }
}
