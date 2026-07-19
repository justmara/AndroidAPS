package app.aaps.core.interfaces.logging

import android.content.Context

interface LoggerUtils {

    fun initialize(appContext: Context)

    val logDirectory: String
    val suffix: String
}