package com.lekaspos.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.lekaspos.BuildConfig
import com.lekaspos.util.ErrorLog
import com.lekaspos.util.Log
import com.lekaspos.util.StrictModeSetup

class LekasApp : Application(), Configuration.Provider {

    // Screens and app-context strings (e.g. the names of the seed roles) in the chosen language.
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this) // crashes and logged failures go to files/logs (no disk work here)
        ErrorReports.init(this) // and, if the shop allowed it, to the developer (D-057; no disk work here)
        if (BuildConfig.DEBUG) StrictModeSetup.enable()
        graph = AppGraph(this) // object creation only — no disk or network here (cold-start budget)
    }

    /**
     * WorkManager starts on first use with this configuration (its start-up provider is removed).
     * When its own database cannot be used (a full phone storage, say), WorkManager throws on its
     * background thread unless a handler is set — the app then crashed a moment after every start
     * (2026-10 review). A till without background jobs still sells; the failure is only logged.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .setInitializationExceptionHandler { e -> Log.e("Background jobs unavailable", e) }
            .setSchedulingExceptionHandler { e -> Log.e("A background job could not be scheduled", e) }
            .build()

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as LekasApp).graph
    }
}
