package io.github.bornaradusin.wallpaperchanger

import android.app.Application
import android.content.Context

class App : Application() {
    val library by lazy { Library(this) }
    val updates by lazy { Updates(this) }

    override fun onCreate() {
        super.onCreate()
        Updates.cleanLeftovers(this)
    }
}

val Context.app: App get() = applicationContext as App
