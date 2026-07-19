import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    const val appVersion = "3.4.2.3"

    // Build label appended to the version number. The version number (appVersion) may change
    // freely (e.g. 3.4.2.1, 3.3.2.0); the label stays constant for this fork.
    // buildName is what shows up as the build name, in the APK file name and as the Google Drive folder.
    const val buildLabel = "Boost_ML+EN"
    val buildName = "${appVersion}_$buildLabel" // e.g. 3.4.2.3_Boost_ML

    const val versionCode = 1500

    const val customPatchVersion = "beta"

    const val ndkVersion = "21.1.6352462"

    const val compileSdk = 36
    const val minSdk = 31
    const val targetSdk = 32
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
