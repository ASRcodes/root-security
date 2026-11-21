package com.asrcodes.dontmissmom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import java.io.File

/** Scroll inside the outer page's gutter, bounded by its real accessibility scroll actions. */
internal class RootSecurityUiHarness(private val context: Context) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    fun findText(text: String, towardBottom: Boolean = true): UiObject2 =
        findInApp("text '$text'", towardBottom, forClick = true,
            bringIntoView = { showOnScreen({ it.text?.toString() == text }, forClick = true) }) {
            device.findObject(By.pkg(context.packageName).text(text))
        }

    /** Invoke the real button's accessibility action after scrolling it into view. */
    fun clickText(text: String, towardBottom: Boolean = true) {
        findText(text, towardBottom)
        var node = ownedNodes().firstOrNull { it.text?.toString() == text }
            ?: error("Visible button text disappeared: $text")
        for (depth in 0..6) {
            if (node.isClickable && node.isEnabled) {
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Button rejected click: $text" }
                instrumentation.waitForIdleSync()
                device.waitForIdle(1_000)
                return
            }
            node = node.parent?.takeIf { it.packageName?.toString() == context.packageName && !it.isScrollable }
                ?: error("No enabled clickable parent for: $text")
        }
        error("No clickable button within parent bounds: $text")
    }

    /** Match the actual editable field, not the read-only selected-example card. */
    fun findEditableText(expected: String, towardBottom: Boolean = true): UiObject2 =
        findInApp("editable input containing '$expected'", towardBottom,
            bringIntoView = { showOnScreen({ it.className?.toString() == "android.widget.EditText" &&
                it.text?.toString()?.contains(expected) == true }, forClick = false) }) {
            device.findObjects(By.pkg(context.packageName).clazz("android.widget.EditText"))
                .firstOrNull { it.isEnabled && it.text?.contains(expected) == true && !it.visibleBounds.isEmpty }
        }

    private fun findInApp(description: String, towardBottom: Boolean, forClick: Boolean = false,
                          bringIntoView: () -> Unit, find: () -> UiObject2?): UiObject2 {
        try {
            instrumentation.waitForIdleSync()
            for (down in listOf(towardBottom, !towardBottom)) {
                for (step in 0..20) {
                    check(device.currentPackageName == context.packageName) { "The test app is not in the foreground" }
                    readyTarget(find, bringIntoView, forClick)?.let { return it }
                    if (step == 20 || !scrollPage(down)) break
                }
            }
            readyTarget(find, bringIntoView, forClick)?.let { return it }
            throw AssertionError("App control was not reachable within the page's scroll bounds: $description")
        } catch (failure: Throwable) {
            captureFailure()
            throw failure
        }
    }

    private fun readyTarget(find: () -> UiObject2?, bringIntoView: () -> Unit, forClick: Boolean): UiObject2? {
        return try {
            val current = find() ?: return null
            // Do not move a control that is already safely visible. SHOW_ON_SCREEN can
            // reposition an otherwise tappable Compose button against a viewport boundary.
            safelyVisibleTarget(current, forClick)?.let { return it }
            // Only a clipped target needs a semantics request. Discard every old UiObject2
            // afterwards, because bringing it into view can replace the Compose node.
            bringIntoView()
            instrumentation.waitForIdleSync()
            device.waitForIdle(1_000)
            safelyVisibleTarget(find() ?: return null, forClick)
        } catch (_: StaleObjectException) {
            null // Re-query after the next bounded step; never click a detached semantics node.
        }
    }

    private fun safelyVisibleTarget(node: UiObject2, forClick: Boolean): UiObject2? {
        var fresh = node
        if (forClick && !fresh.isClickable) {
            var parent = fresh.parent
            for (depth in 0..5) {
                val candidate = parent ?: break
                if (candidate.applicationPackage != context.packageName || candidate.isScrollable) break
                if (candidate.isClickable) { fresh = candidate; break }
                parent = candidate.parent
            }
        }
        if (forClick && fresh.isClickable && !fresh.isEnabled) return null
        val bounds = fresh.visibleBounds
        if (bounds.isEmpty) return null
        val viewport = pageNode()?.let { Rect().also(it::getBoundsInScreen) }
            ?: Rect(0, 0, device.displayWidth, device.displayHeight)
        val inset = (12 * context.resources.displayMetrics.density).toInt()
        // Action targets need a safe click centre. Read-only headings only need to be
        // visible: a clipped top heading is still valid evidence of the requested page.
        if (fresh.isClickable || fresh.className == "android.widget.EditText") {
            if (bounds.centerX() !in (viewport.left + inset)..(viewport.right - inset) ||
                bounds.centerY() !in (viewport.top + inset)..(viewport.bottom - inset)) return null
        } else if (!Rect.intersects(bounds, viewport)) return null
        return fresh
    }

    private fun ownedNodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || count++ >= 500 || node.packageName?.toString() != context.packageName) return
            nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return nodes
    }

    private fun pageNode(): AccessibilityNodeInfo? = ownedNodes().filter {
        it.isScrollable && it.isVisibleToUser && it.className?.toString() != "android.widget.EditText"
    }.map { it to Rect().also(it::getBoundsInScreen) }
        .filter { it.second.height() > device.displayHeight / 4 }
        .maxByOrNull { it.second.width().toLong() * it.second.height() }?.first

    private fun showOnScreen(matches: (AccessibilityNodeInfo) -> Boolean, forClick: Boolean) {
        var node = ownedNodes().firstOrNull(matches) ?: return
        if (forClick && !node.isClickable) {
            var parent = node.parent
            for (depth in 0..5) {
                val candidate = parent ?: break
                if (candidate.packageName?.toString() != context.packageName || candidate.isScrollable) break
                if (candidate.isClickable) { node = candidate; break }
                parent = candidate.parent
            }
        }
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
    }

    private fun scrollPage(towardBottom: Boolean): Boolean {
        val page = pageNode() ?: return false
        val action = if (towardBottom) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        // Compose removes the corresponding action at a boundary. Stop that direction there;
        // retain that boundary check, but use overlapping steps so a boundary-clipped button
        // is not skipped by a full-page accessibility jump. The 10dp gutter is outside the
        // page's 22dp content padding, so the gesture cannot scroll the nested text editor.
        if (page.actionList.none { it.id == action }) return false
        val viewport = Rect().also(page::getBoundsInScreen)
        if (!viewport.intersect(0, 0, device.displayWidth, device.displayHeight)) return false
        val gutter = (10 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val x = viewport.left + gutter.coerceAtMost(viewport.width() / 4)
        val upper = viewport.top + viewport.height() / 4
        val lower = viewport.bottom - viewport.height() / 4
        if (!device.swipe(x, if (towardBottom) lower else upper,
                x, if (towardBottom) upper else lower, 80)) return false
        instrumentation.waitForIdleSync()
        device.waitForIdle(1_000)
        return true
    }

    /** Only the owned app's active window is captured; password fields are redacted in XML. */
    private fun captureFailure() = runCatching {
        if (device.currentPackageName != context.packageName) return@runCatching
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@runCatching
        if (root.packageName?.toString() != context.packageName) return@runCatching
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "test-output").apply { mkdirs() }
        val stem = "root-security-ui-failure-${System.currentTimeMillis()}"
        File(directory, "$stem.xml").writer(Charsets.UTF_8).use { writer ->
            val xml = Xml.newSerializer().apply { setOutput(writer); startDocument("UTF-8", true) }
            var count = 0
            fun writeNode(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30 || count++ >= 500 || node.packageName?.toString() != context.packageName) return
                val bounds = Rect().also(node::getBoundsInScreen)
                xml.startTag(null, "node")
                xml.attribute(null, "class", node.className?.toString().orEmpty())
                xml.attribute(null, "bounds", bounds.toShortString())
                xml.attribute(null, "scrollable", node.isScrollable.toString())
                xml.attribute(null, "clickable", node.isClickable.toString())
                xml.attribute(null, "text", if (node.isPassword) "[redacted]" else node.text?.toString().orEmpty())
                xml.attribute(null, "description", if (node.isPassword) "[redacted]" else node.contentDescription?.toString().orEmpty())
                for (index in 0 until node.childCount) node.getChild(index)?.let { writeNode(it, depth + 1) }
                xml.endTag(null, "node")
            }
            writeNode(root, 0)
            xml.endDocument()
        }
        val frame = instrumentation.uiAutomation.takeScreenshot() ?: return@runCatching
        try {
            val bounds = Rect().also(root::getBoundsInScreen)
            if (bounds.intersect(0, 0, frame.width, frame.height) && !bounds.isEmpty) {
                val owned = Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height())
                try { File(directory, "$stem.png").outputStream().use { owned.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { if (owned !== frame) owned.recycle() }
            }
        } finally { frame.recycle() }
    }
}
