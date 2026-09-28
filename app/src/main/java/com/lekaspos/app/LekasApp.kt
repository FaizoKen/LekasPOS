package com.lekaspos.app

import android.app.Application
import android.content.Context
import com.lekaspos.BuildConfig
import com.lekaspos.util.StrictModeSetup

class LekasApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) StrictModeSetup.enable()
        graph = AppGraph(this) // object creation only — no disk or network here (cold-start budget)
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as LekasApp).graph
    }
}
