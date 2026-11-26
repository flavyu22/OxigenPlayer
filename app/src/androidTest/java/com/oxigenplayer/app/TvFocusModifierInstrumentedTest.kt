// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class TvFocusModifierInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvFocusableExposesButtonAndSelectionSemantics() {
        composeRule.setContent {
            Box(
                Modifier
                    .testTag("tv-control")
                    .tvFocusable(isSelected = true, onClick = {}),
            )
        }

        composeRule.onNodeWithTag("tv-control")
            .assertHasClickAction()
            .assertIsSelected()
    }
}
