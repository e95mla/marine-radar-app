package com.example.marineradar

import android.app.Application
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.debug.PacketLogger

class MarineRadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        PacketLogger.init(this)
        FileLogger.log("INFO", "App startad")
    }
}
