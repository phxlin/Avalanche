package com.avalanche.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.domain.PayoffCalculator
import com.avalanche.app.domain.PlanDebt
import com.avalanche.app.domain.Strategy
import com.avalanche.app.ui.plan.ComparisonCard
import com.avalanche.app.ui.theme.AvalancheTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Avalanche vs. snowball boxes are choices: tapping one selects that strategy. */
@RunWith(AndroidJUnit4::class)
class ComparisonCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val debts = listOf(
        PlanDebt(1, "Card", true, 4500.0, 25.74, 150.0),
        PlanDebt(2, "Loan", false, 34000.0, 8.99, 720.0),
    )
    private val start = YearMonth.of(2026, 9)
    private val avalanche = PayoffCalculator.simulate(debts, 300.0, Strategy.AVALANCHE, start = start)
    private val snowball = PayoffCalculator.simulate(debts, 300.0, Strategy.SNOWBALL, start = start)

    private fun show(current: Strategy, onSelect: (Strategy) -> Unit) =
        compose.setContent { AvalancheTheme { ComparisonCard(avalanche, snowball, current, onSelect) } }

    @Test
    fun tappingSnowballSelectsSnowball() {
        var picked: Strategy? = null
        show(Strategy.AVALANCHE) { picked = it }

        compose.onNodeWithText("Snowball").performClick()

        assertEquals(Strategy.SNOWBALL, picked)
    }

    @Test
    fun tappingAvalancheSelectsAvalanche() {
        var picked: Strategy? = null
        show(Strategy.SNOWBALL) { picked = it }

        compose.onNodeWithText("Avalanche").performClick()

        assertEquals(Strategy.AVALANCHE, picked)
    }

    @Test
    fun nothingIsSelectedUntilYouTap() {
        var picked: Strategy? = null
        show(Strategy.AVALANCHE) { picked = it }

        assertNull(picked)
    }

    @Test
    fun theStrategyInUseIsMarkedAndAnnouncedAsSelected() {
        show(Strategy.SNOWBALL) {}

        compose.onNodeWithText("In use").assertExists()
        compose.onNodeWithText("Snowball").assertIsSelected()
        // Both boxes are radio-style choices, so a screen reader says so.
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(2)
    }
}
