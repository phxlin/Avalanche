package com.avalanche.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.ui.components.DebtCard
import com.avalanche.app.ui.theme.AvalancheTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout checks for the debt list card, on real Compose measurement rather than a mock of it. */
@RunWith(AndroidJUnit4::class)
class DebtCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun debt(name: String = "Card A", balance: Double = 4500.0) = DebtEntity(
        id = 1, name = name, type = "CREDIT_CARD", originalBalance = 5000.0, currentBalance = balance,
        apr = 25.74, minPayment = 150.0, createdDay = 0, lastAccrualDay = 0,
    )

    // The card merges its children into one accessibility node, so these assertions look at the individual
    // text elements (the unmerged tree). Checking the merged node passes even with the bottom clipped away.
    @Test
    fun threeTagsWrapAndTheWholeCardStaysVisible() {
        // Regression: with three tags in one row the last was squeezed into a sliver and the card's bottom (the
        // progress bar and "paid off" line) was clipped away. 360dp is a typical phone width.
        compose.setContent {
            AvalancheTheme {
                Box(Modifier.width(360.dp)) { DebtCard(debt(), onClick = {}, focus = true, paidThisMonth = true) }
            }
        }

        compose.onNodeWithText("Credit card", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Extra goes here", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Paid this month", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("$4,500.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Minimum", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("10% paid off", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aCardWithOneTagStillShowsEverything() {
        compose.setContent {
            AvalancheTheme { Box(Modifier.width(360.dp)) { DebtCard(debt(), onClick = {}) } }
        }

        compose.onNodeWithText("Credit card", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("10% paid off", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theTagsOnlyAppearWhenTheyApply() {
        compose.setContent {
            AvalancheTheme { Box(Modifier.width(360.dp)) { DebtCard(debt(), onClick = {}, focus = false, paidThisMonth = false) } }
        }

        compose.onNodeWithText("Extra goes here", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Paid this month", useUnmergedTree = true).assertDoesNotExist()
    }
}
