import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-app-dependencies")
    id("test-app-dependencies")
    id("jacoco-app-dependencies")
}

repositories {
    google()
    mavenCentral()
}

fun generateGitBuild(): String {
    val stringBuilder: StringBuilder = StringBuilder()
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--always")
            standardOutput = stdout
        }
        val commitObject = stdout.toString().trim()
        stringBuilder.append(commitObject)
    } catch (ignored: Exception) {
        stringBuilder.append("NoGitSystemAvailable")
    }
    return stringBuilder.toString()
}

fun generateDate(): String {
    val stringBuilder: StringBuilder = StringBuilder()
    // showing only date prevents app to rebuild everytime
    stringBuilder.append(SimpleDateFormat("yyyy.MM.dd").format(Date()))
    return stringBuilder.toString()
}


android {
    namespace = "app.aaps.wear"

    defaultConfig {
        minSdk = Versions.wearMinSdk
        targetSdk = Versions.wearTargetSdk

        buildConfigField("String", "BUILDVERSION", "\"${generateGitBuild()}-${generateDate()}\"")
    }

    flavorDimensions.add("standard")
    productFlavors {
        create("full") {
            isDefault = true
            applicationId = "info.nightscout.androidaps"
            dimension = "standard"
            versionName = Versions.appVersion
        }
        create("pumpcontrol") {
            applicationId = "info.nightscout.aapspumpcontrol"
            dimension = "standard"
            versionName = Versions.appVersion + "-pumpcontrol"
        }
        create("aapsclient") {
            applicationId = "info.nightscout.aapsclient"
            dimension = "standard"
            versionName = Versions.appVersion + "-aapsclient"
        }
        create("aapsclient2") {
            applicationId = "info.nightscout.aapsclient2"
            dimension = "standard"
            versionName = Versions.appVersion + "-aapsclient2"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var flavor = variant.productFlavors[0].name
                if (flavor == "full") flavor = "aaps"
                val date = SimpleDateFormat("yyMMdd").format(Date())
                val fileName = "wear-${flavor}-${version}-custom-${date}.apk"
                output.outputFileName = fileName
            }
    }
}

allprojects {
    repositories {
    }
}


dependencies {
    implementation(project(":shared:impl"))
    implementation(project(":core:interfaces"))

    implementation(Libs.AndroidX.appCompat)
    implementation(Libs.AndroidX.core)
    implementation(Libs.AndroidX.legacySupport)
    implementation(Libs.AndroidX.preference)
    implementation(Libs.AndroidX.Wear.wear)
    implementation(Libs.AndroidX.Wear.tiles)
    implementation(Libs.AndroidX.constraintLayout)

    testImplementation(project(":shared:tests"))

    compileOnly(Libs.Google.Android.Wearable.wearable)
    implementation(Libs.Google.Android.Wearable.wearableSupport)
    implementation(Libs.Google.Android.PlayServices.wearable)
    implementation(files("${rootDir}/wear/libs/ustwo-clockwise-debug.aar"))
    implementation(files("${rootDir}/wear/libs/wearpreferenceactivity-0.5.0.aar"))
    implementation(files("${rootDir}/wear/libs/hellocharts-library-1.5.8.aar"))

    implementation(Libs.KotlinX.coroutinesCore)
    implementation(Libs.KotlinX.coroutinesAndroid)
    implementation(Libs.KotlinX.coroutinesGuava)
    implementation(Libs.KotlinX.coroutinesPlayServices)
    implementation(Libs.KotlinX.datetime)
    implementation(Libs.Kotlin.stdlibJdk8)

    kapt(Libs.Dagger.androidProcessor)
    kapt(Libs.Dagger.compiler)
}
