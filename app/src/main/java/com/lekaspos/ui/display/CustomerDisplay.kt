package com.lekaspos.ui.display

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import com.lekaspos.app.AppGraph
import com.lekaspos.util.Log

/**
 * Keeps the customer screen ([CustomerScreen], D-069) on the second display while the app is in front (main
 * thread). Android shows a presentation only through an activity, so the screen moves with the activity in
 * front: when the one holding it stops (another screen of the app opened over it), the new one takes it over;
 * without a presentation, Android would mirror the till's own screen — reports and costs — to the customers.
 * A second display is any presentation display: Miracast ("Screen mirroring", "Smart View", "Wireless
 * display"), HDMI, USB-C. Google Cast's "Cast screen" only mirrors and is not one.
 */
class CustomerDisplay(private val app: Application, private val graph: AppGraph) :
    Application.ActivityLifecycleCallbacks, DisplayManager.DisplayListener {

    private var screen: CustomerScreen? = null
    private var host: Activity? = null
    private var hostStopped = false
    private var resumed: Activity? = null
    private var listening = false

    private val displays: DisplayManager get() = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** The second display the customer screen goes on, if one is connected (null: none). */
    fun display(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.isValid }

    /** The activity the customer screen is shown through (tests). */
    val hostedBy: Activity? get() = host

    /** The customer screen is on a second display now (Settings, tests). */
    val showing: CustomerScreen? get() = screen?.takeIf { it.isShowing }

    /** The setting changed, or a display came or went: shown, moved or taken away accordingly. */
    fun refresh() {
        val a = resumed
        if (a == null) detach() else attach(a)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        if (!listening) {
            listening = true
            displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        }
        if (screen == null || hostStopped || host?.isDestroyed == true || screen?.isShowing != true) attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity !== host) return
        hostStopped = true
        // Another screen of the app is in front: it holds the customer screen from now on.
        resumed?.let { attach(it) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) detach()
    }

    /** Shows the customer screen through [a] on the second display (nothing to do when it already is). */
    private fun attach(a: Activity) {
        val display = if (graph.settings.device.value.customerScreen) display() else null
        if (display == null || a.isFinishing || a.isDestroyed) return detach()
        val current = screen
        if (current != null && current.isShowing && host === a && !hostStopped && current.display.displayId == display.displayId) return
        detach()
        val s = CustomerScreen(a, display, graph)
        try {
            s.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w("The second display went away", e)
            return
        } catch (e: WindowManager.BadTokenException) {
            Log.w("The customer screen could not be shown", e)
            return
        }
        screen = s
        host = a
        hostStopped = false
    }

    private fun detach() {
        val s = screen
        screen = null
        host = null
        hostStopped = false
        if (s != null && s.isShowing) {
            try {
                s.dismiss()
            } catch (e: IllegalArgumentException) {
                Log.w("The customer screen was already gone", e) // its display was removed
            }
        }
    }

    override fun onDisplayAdded(displayId: Int) = refresh()

    override fun onDisplayRemoved(displayId: Int) = refresh()

    override fun onDisplayChanged(displayId: Int) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
