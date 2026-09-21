package com.avalanche.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.ui.components.ConfirmDialog
import com.avalanche.app.ui.components.DELETE_CONFIRM_PHRASE
import com.avalanche.app.ui.theme.AvalancheTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Delete all data" can't be undone, so its dialog makes the user type DELETE first. */
@RunWith(AndroidJUnit4::class)
class ConfirmDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private var confirmed = 0
    private var dismissed = 0

    private fun show(phrase: String?) = compose.setContent {
        AvalancheTheme {
            ConfirmDialog(
                title = "Delete all data?",
                text = "Every debt and payment will be removed.",
                confirmLabel = "Delete everything",
                destructive = true,
                confirmPhrase = phrase,
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }
    }

    @Test
    fun theConfirmButtonStaysDisabledUntilTheWordIsTyped() {
        show(DELETE_CONFIRM_PHRASE)

        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("DELET")
        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("E")
        compose.onNodeWithText("Delete everything").assertIsEnabled()
    }

    @Test
    fun aDisabledConfirmButtonDoesNothingAndTheWordThenConfirmsInAnyCase() {
        show(DELETE_CONFIRM_PHRASE)

        compose.onNodeWithText("Delete everything").performClick()
        assertEquals(0, confirmed)

        compose.onNode(hasSetTextAction()).performTextInput("delete")
        compose.onNodeWithText("Delete everything").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun cancelNeverConfirmsEvenAfterTheWordIsTyped() {
        show(DELETE_CONFIRM_PHRASE)

        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun aDialogWithoutAPhraseConfirmsStraightAway() {
        show(null)

        compose.onNodeWithText("Delete everything").assertIsEnabled()
        compose.onNodeWithText("Delete everything").performClick()

        assertEquals(1, confirmed)
    }
}
