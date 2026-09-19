package com.kamuinet.client

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KamuiApplication : Application() {
    companion object {
        lateinit var instance: KamuiApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
