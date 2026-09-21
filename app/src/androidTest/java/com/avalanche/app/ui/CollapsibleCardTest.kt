package com.avalanche.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.ui.components.CollapsibleCard
import com.avalanche.app.ui.theme.AvalancheTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Plan tab's secondary cards fold away so the screen opens on the answer. */
@RunWith(AndroidJUnit4::class)
class CollapsibleCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(startOpen: Boolean) = compose.setContent {
        AvalancheTheme {
            var open by rememberSaveable { mutableStateOf(startOpen) }
            CollapsibleCard(title = "Lump sum", subtitle = "Bonus or refund", expanded = open, onToggle = { open = !open }) {
                Text("Inside the card")
            }
        }
    }

    private fun stateIs(description: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    @Test
    fun aFoldedCardShowsItsHeaderButNotItsBody() {
        show(startOpen = false)

        compose.onNodeWithText("Lump sum").assertExists()
        compose.onNodeWithText("Bonus or refund").assertExists()
        compose.onNodeWithText("Inside the card").assertDoesNotExist()
    }

    @Test
    fun tappingTheHeaderOpensAndClosesTheBody() {
        show(startOpen = false)

        compose.onNodeWithText("Lump sum").performClick()
        compose.onNodeWithText("Inside the card").assertExists()

        compose.onNodeWithText("Lump sum").performClick()
        compose.onNodeWithText("Inside the card").assertDoesNotExist()
    }

    @Test
    fun theHeaderAnnouncesWhetherTheCardIsOpen() {
        show(startOpen = false)
        compose.onNodeWithText("Lump sum", useUnmergedTree = false).assert(stateIs("Collapsed"))

        compose.onNodeWithText("Lump sum").performClick()

        compose.onNodeWithText("Lump sum").assert(stateIs("Expanded"))
    }

    @Test
    fun aCardCanStartOpen() {
        show(startOpen = true)

        compose.onNodeWithText("Inside the card").assertExists()
    }
}
