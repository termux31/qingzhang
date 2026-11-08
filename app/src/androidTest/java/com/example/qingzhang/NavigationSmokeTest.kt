package com.example.qingzhang

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.qingzhang.notifications.NoticeKind
import com.example.qingzhang.notifications.NoticeTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensAddFormAndStatistics() {
        composeRule.onNodeWithText("轻账").assertIsDisplayed()
        composeRule.onAllNodesWithText("记一笔").onFirst().performClick()
        composeRule.onNodeWithText("保存账目").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onAllNodesWithText("统计").onFirst().performClick()
        composeRule.onNodeWithText("收支趋势").assertIsDisplayed()
        composeRule.onAllNodesWithText("我的").onFirst().performClick()
        composeRule.onNodeWithText("软件更新").performClick()
        composeRule.onNodeWithText("更新源").assertIsDisplayed()
    }

    @Test
    fun retainsUnsavedAmountWhenActivityIsRecreated() {
        composeRule.onAllNodesWithText("记一笔").onFirst().performClick()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("123.45")
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("123.45").assertIsDisplayed()
    }

    @Test
    fun switchesThemeAndRetainsItWhenActivityIsRecreated() {
        composeRule.onAllNodesWithText("我的").onFirst().performClick()
        val switch = composeRule.onNodeWithContentDescription("深色主题")
        if (composeRule.onAllNodesWithText("深色").fetchSemanticsNodes().isNotEmpty()) {
            switch.performClick()
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("白色").fetchSemanticsNodes().isNotEmpty() }
        switch.assertIsOff().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("深色").fetchSemanticsNodes().isNotEmpty() }
        switch.assertIsOn()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("深色").fetchSemanticsNodes().isNotEmpty() }
        switch.assertIsOn()
        composeRule.activityRule.scenario.onActivity {
            assertFalse(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars)
        }

        switch.performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("白色").fetchSemanticsNodes().isNotEmpty() }
        switch.assertIsOff()
        composeRule.activityRule.scenario.onActivity {
            assertTrue(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars)
        }
    }

    @Test
    fun opensLivingPlanAndSavingsFromMine() {
        composeRule.onAllNodesWithText("我的").onFirst().performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("大学生活费计划"))
        composeRule.onNodeWithText("大学生活费计划").performClick()
        composeRule.onNodeWithText("新建 30 天计划").performClick()
        composeRule.onNodeWithText("新建生活费计划").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithText("管理攒钱目标").performClick()
        composeRule.onNodeWithText("新建攒钱目标").performClick()
        composeRule.onAllNodesWithText("目标名称").onFirst().assertIsDisplayed()
    }

    @Test
    fun opensReminderSettingsAndNavigatesFromNotificationIntents() {
        composeRule.onAllNodesWithText("我的").onFirst().performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("通知设置"))
        composeRule.onNodeWithText("通知设置").performClick()
        composeRule.onNodeWithText("提醒时间 21:00").assertIsDisplayed()

        fun open(kind: NoticeKind, pageText: String) {
            composeRule.activityRule.scenario.onActivity { it.startActivity(NoticeTarget(kind, 1).intent(it)) }
            val page = hasText(pageText, substring = true)
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(page).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(page).assertIsDisplayed()
        }

        open(NoticeKind.DAILY, "保存账目")
        open(NoticeKind.GOAL, "新建攒钱目标")
        open(NoticeKind.SETTLEMENT, "新建 30 天计划")
        open(NoticeKind.OVER_BUDGET, "共 ")
    }
}
