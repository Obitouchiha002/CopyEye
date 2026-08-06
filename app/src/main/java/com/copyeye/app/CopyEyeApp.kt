package com.copyeye.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.copyeye.app.capture.FrameStore

class CopyEyeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // A pending frame is several megabytes and is only ever useful for a few seconds, so it is
        // the first thing to go under pressure.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            FrameStore.clear()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            container.releaseHeavyResources()
        }
    }
}
