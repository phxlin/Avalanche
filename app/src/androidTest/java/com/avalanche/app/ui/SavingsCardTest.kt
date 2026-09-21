package com.avalanche.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.domain.PayoffCalculator
import com.avalanche.app.domain.PlanDebt
import com.avalanche.app.domain.SavingsProjector
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.domain.Strategy
import com.avalanche.app.ui.plan.SavingsCard
import com.avalanche.app.ui.plan.SavingsDialog
import com.avalanche.app.ui.theme.AvalancheTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The savings card on the Plan tab and the dialog that fills it in. */
@RunWith(AndroidJUnit4::class)
class SavingsCardTest {
    @get:Rule
    val compose = createComposeRule()

    // A 1,000 debt paid 100 a month at 0%: debt-free in ten months.
    private val plan = PayoffCalculator.simulate(
        listOf(PlanDebt(1, "Card", true, 1000.0, 0.0, 100.0)), 0.0, Strategy.AVALANCHE, start = YearMonth.of(2026, 9),
    )
    private val setup = SavingsSetup(balance = 0.0, monthlyIncome = 2000.0, percentSaved = 10.0, apy = 0.0)

    private var edits = 0
    private var saved: SavingsSetup? = null
    private var removed = 0
    private var dismissed = 0

    private fun showCard(projection: com.avalanche.app.domain.SavingsProjection?) = compose.setContent {
        AvalancheTheme { SavingsCard(projection, plan, onEdit = { edits++ }) }
    }

    private fun showDialog(initial: SavingsSetup?, withRemove: Boolean) = compose.setContent {
        AvalancheTheme {
            SavingsDialog(
                initial = initial,
                onSave = { saved = it },
                onRemove = if (withRemove) ({ removed++ }) else null,
                onDismiss = { dismissed++ },
            )
        }
    }

    private fun typeIntoField(index: Int, text: String) = compose.onAllNodes(hasSetTextAction())[index].performTextInput(text)

    // ---------- the card ----------

    @Test
    fun withoutSavingsTheCardInvitesTheUserToSetThemUp() {
        showCard(null)

        compose.onNodeWithText("Set up savings").performClick()

        assertEquals(1, edits)
    }

    @Test
    fun withSavingsTheCardShowsTheSetupAndWhenTheSavingsPassTheDebt() {
        showCard(SavingsProjector.project(setup, plan, monthlyDebtPayment = 100.0))

        compose.onNodeWithText("Saved today").assertExists()
        compose.onNodeWithText("Saving each month").assertExists()
        compose.onNodeWithText("Your savings pass what you still owe in", substring = true).assertExists()
        compose.onNodeWithText("you'd have", substring = true).assertExists()
        compose.onNodeWithText("of your income", substring = true).assertExists()

        compose.onNodeWithText("Edit savings").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun savingsThatAlreadyCoverTheDebtSaySoInsteadOfGivingADate() {
        showCard(SavingsProjector.project(setup.copy(balance = 5000.0), plan, 100.0))

        compose.onNodeWithText("You already have more saved than you owe.").assertExists()
        compose.onNodeWithText("Your savings pass what you still owe in", substring = true).assertDoesNotExist()
    }

    // ---------- the dialog ----------

    @Test
    fun saveStaysDisabledUntilIncomeShareAndRateAreFilledIn() {
        showDialog(null, withRemove = false)

        compose.onNodeWithText("Save").assertIsNotEnabled()
        typeIntoField(1, "4200")
        typeIntoField(2, "15")
        compose.onNodeWithText("Save").assertIsNotEnabled() // the rate is still missing
        typeIntoField(3, "4.35")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun savingHandsBackWhatWasTypedAndAnEmptyBalanceMeansZero() {
        showDialog(null, withRemove = false)

        typeIntoField(1, "4200")
        typeIntoField(2, "15")
        typeIntoField(3, "4.35")
        compose.onNodeWithText("Save").performClick()

        assertEquals(SavingsSetup(0.0, 4200.0, 15.0, 4.35), saved)
    }

    @Test
    fun anOutOfRangePercentBlocksSavingAndSaysWhy() {
        showDialog(null, withRemove = false)

        typeIntoField(1, "4200")
        typeIntoField(2, "150")
        typeIntoField(3, "4")

        compose.onNodeWithText("Can't be more than 100%").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun editingStartsFromTheSavedValuesAndOffersToRemoveThem() {
        showDialog(SavingsSetup(2500.5, 4200.0, 12.5, 4.35), withRemove = true)

        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("2500.5").assertExists()
        compose.onNodeWithText("Remove savings").performClick()

        assertEquals(1, removed)
        assertNull(saved)
    }

    @Test
    fun theFirstTimeThereIsNothingToRemoveAndCancelDoesNotSave() {
        showDialog(null, withRemove = false)

        compose.onNodeWithText("Remove savings").assertDoesNotExist()
        typeIntoField(1, "4200")
        typeIntoField(2, "15")
        typeIntoField(3, "4")
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(saved)
    }

    @Test
    fun aSavedZeroRateOrZeroShareIsPrefilledAsZeroSoSavingStaysPossible() {
        // Regression: zero was prefilled as an empty box, and both fields are required, so Save stayed disabled.
        showDialog(SavingsSetup(balance = 0.0, monthlyIncome = 4200.0, percentSaved = 0.0, apy = 0.0), withRemove = true)

        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onAllNodes(hasSetTextAction())[2].assertTextContains("0")
        compose.onAllNodes(hasSetTextAction())[3].assertTextContains("0")
    }
}
