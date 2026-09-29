package com.lekaspos.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.lekaspos.BuildConfig
import com.lekaspos.util.StrictModeSetup

class LekasApp : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) StrictModeSetup.enable()
        graph = AppGraph(this) // object creation only — no disk or network here (cold-start budget)
    }

    /** WorkManager starts on first use with this configuration (its start-up provider is removed). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR).build()

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as LekasApp).graph
    }
}
