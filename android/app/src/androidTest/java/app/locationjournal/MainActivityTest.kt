package app.locationjournal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MainActivityTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstRunShowsPrivacyOnboarding() {
        composeRule.onNodeWithText("Location journal", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("What the app stores").assertIsDisplayed()
        composeRule.onNodeWithText("Allow location and continue").assertIsDisplayed()
        composeRule.onNodeWithText("Continue without tracking").assertIsDisplayed()
    }
}
