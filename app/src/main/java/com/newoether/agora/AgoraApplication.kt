package com.newoether.agora

import android.app.Application
import android.content.ComponentCallbacks2
import com.newoether.agora.di.AppContainer
import com.newoether.agora.ui.components.trimLatexBitmapCache
import com.newoether.agora.util.CrashReporter

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 *
 * Owns the process-scoped [AppContainer] so that shared singletons (data layer, providers,
 * generation infrastructure) outlive any single Activity/ViewModel and are reachable from
 * background components (Workers, scheduled task execution) — not just the UI.
 */
class AgoraApplication : Application() {
    /** Process-lifetime dependency container. The single source of shared singletons. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container.startRunRecovery()
        // Arm scheduled task alarms for this process (idempotent; also re-armed after boot).
        container.automationScheduler.start()
    }

    /**
     * 系统内存压力回调。当 level 达到 MODERATE 或更紧急时，释放 LaTeX Bitmap 缓存持有的
     * 原生内存（minSdk=24，API 24-25 需显式 recycle，API 26+ 由 GC 管理但显式释放仍有益）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            trimLatexBitmapCache()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trimLatexBitmapCache()
    }
}
