package me.d3sox.nevumi

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import org.junit.Assert.assertFalse

/**
 * Slow emulators sometimes report a system app, such as the launcher, as not responding. That dialog covers the
 * screen under test until someone answers it, so keep waiting for the app. Nevumi's own dialog fails the test instead.
 * Returns whether a dialog was answered.
 */
internal fun waitOnNotRespondingDialog(root: AccessibilityNodeInfo): Boolean {
    val title = root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").firstOrNull { it.isVisibleToUser }?.text?.toString()
    assertFalse("Nevumi stopped responding: $title", title?.contains("Nevumi") == true)
    return root.findAccessibilityNodeInfosByViewId("android:id/aerr_wait").firstOrNull { it.isVisibleToUser }
        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
}

/**
 * Clicks the first clickable node matching [matches] in a system dialog. Slow emulators can show a permission dialog
 * without moving input focus to it, so this searches every application window. Skip the app tree and system bars
 * so querying unrelated UI cannot consume the time allowed for the dialog. Answers system "not responding" dialogs
 * that cover it.
 */
internal suspend fun UiAutomation.clickSystemDialogButton(matches: (AccessibilityNodeInfo) -> Boolean) {
    serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || node.packageName?.toString() == appPackage) return false
        if (node.isClickable && matches(node)) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return (0 until node.childCount).any { click(node.getChild(it)) }
    }
    while (!windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && click(it.root) } &&
        !click(rootInActiveWindow)) {
        windows.forEach { window -> window.root?.let(::waitOnNotRespondingDialog) }
        delay(50)
    }
}
