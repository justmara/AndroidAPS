package app.aaps.implementation.logging

import android.content.ContentResolver
import android.content.Context
import android.os.Environment
import android.provider.DocumentsContract
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.FileListProvider
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.android.LogcatAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.rolling.helper.CompressionMode
import ch.qos.logback.core.util.FileSize
import ch.qos.logback.core.util.StatusPrinter
import dagger.Reusable
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * This class provides several methods for log-handling (eg. sending logs as emails).
 */
@Reusable
class LoggerUtilsImpl @Inject constructor(
    private val fileListProvider: FileListProvider,
    private val config: Config
) : LoggerUtils {

    private lateinit var contentResolver: ContentResolver
    private lateinit var packageName: String
    private lateinit var logsPath: String
    private var isFallback: Boolean = false

    override val logDirectory: String
        get() = logsPath

    override val suffix = ".log.zip"

    private fun getLogcatAppender(context: LoggerContext): LogcatAppender {
        val logcatMsg = PatternLayoutEncoder().also {
            it.context = context
            it.pattern = "[%thread]: %msg%n"
            it.start()
        }
        val logcatTag = PatternLayoutEncoder().also {
            it.context = context
            it.pattern = "%logger{0}"
            it.start()
        }
        return LogcatAppender().also {
            it.context = context
            it.tagEncoder = logcatTag
            it.encoder = logcatMsg
            it.start()
        }
    }

    private fun getFileAppender(context: LoggerContext, rootPath: String): RollingFileAppender<ILoggingEvent> {
        val msg = PatternLayoutEncoder().also {
            it.context = context
            it.pattern = "%d{HH:mm:ss.SSS} [%thread] %.-1level/%logger: %msg%n"
            it.start()
        }
        val appender = RollingFileAppender<ILoggingEvent>().also {
            it.context = context
            it.file = "$rootPath/AndroidAPS.log"
            it.encoder = msg
        }
        val rollingPolicy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>().also {
            it.context = context
            it.fileNamePattern = "$rootPath/AndroidAPS.%d{yyyy-MM-dd}.%i.log.zip"
            if (config.isEngineeringMode()) {
                it.maxHistory = 1000
                it.setMaxFileSize(FileSize(FileSize.MB_COEFFICIENT * 25))
                it.setTotalSizeCap(FileSize(FileSize.GB_COEFFICIENT * 10))
            } else {
                it.maxHistory = 300
                it.setMaxFileSize(FileSize(FileSize.MB_COEFFICIENT * 25))
                it.setTotalSizeCap(FileSize(FileSize.GB_COEFFICIENT * 2))
            }
            it.setParent(appender)
        }
        rollingPolicy.start()
        appender.rollingPolicy = rollingPolicy
        appender.start()
        return appender
    }

    private fun getLogsPath(context: Context): String {
        val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
        val fallback = filesDir.path
        val uri = fileListProvider.ensureLogsDirExists()?.uri ?: return fallback
        if (!Environment.isExternalStorageManager()) {
            isFallback = true
            return fallback
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (uri.authority == "com.android.externalstorage.documents") {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex())
                if (split[0] == "primary")
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
            }
        }

        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path ?: fallback
            else   -> fallback
        }
    }

    override fun initialize(appContext: Context) {
        contentResolver = appContext.contentResolver
        packageName = appContext.packageName
        logsPath = getLogsPath(appContext)

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.stop()

        val logcatAppender = getLogcatAppender(context)
        val fileAppender = getFileAppender(context, logsPath)

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.level = Level.DEBUG
        rootLogger.addAppender(fileAppender)
        rootLogger.addAppender(logcatAppender)
        StatusPrinter.print(context)

        if (isFallback)
            rootLogger.warn("CORE: No 'all files access' permission - logback is falling back to Android/data/")
    }
}