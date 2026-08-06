import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  // alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.vlcaiplayer.pwtqy"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters.addAll(listOf("arm64-v8a"))
    }
  }

  signingConfigs {
    create("release") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// googleServices {
//   missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
// }


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.exoplayer.dash)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.session)
  implementation(libs.jellyfin.media3.ffmpeg.decoder)
  implementation(libs.libvlc.all)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

val sysOutputDirPath = file("/output").absolutePath
val localOutputDirPath = file("${rootDir}/output").absolutePath
val buildOutputsDirPath = file("${rootDir}/.build-outputs").absolutePath
val dbgFilePath = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath
val relFilePath = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath

tasks.register("autoExportApkToSystemOutput") {
  dependsOn("assembleDebug")
  val dbgPath = dbgFilePath
  val sysDir = sysOutputDirPath
  val localDir = localOutputDirPath
  val buildDir = buildOutputsDirPath
  doLast {
    val isValidZip = { f: File ->
      if (f.exists() && f.length() > 10_000_000) {
        try {
          val zip = ZipFile(f)
          val hasEntries = zip.entries().hasMoreElements()
          zip.close()
          hasEntries
        } catch (e: Exception) {
          false
        }
      } else {
        false
      }
    }

    val targets = listOf(sysDir, localDir, buildDir)
    val src = File(dbgPath)
    if (isValidZip(src)) {
      targets.forEach { dirPath ->
        val destDir = File(dirPath)
        destDir.mkdirs()

        // Copy as app-debug.apk
        val dbgTarget = File(destDir, "app-debug.apk")
        val dbgTmp = File(destDir, "app-debug.apk.tmp")
        src.copyTo(dbgTmp, overwrite = true)
        dbgTmp.renameTo(dbgTarget)

        // Copy as app-release.apk if release doesn't exist
        val relTarget = File(destDir, "app-release.apk")
        if (!relTarget.exists() || relTarget.length() < 10_000_000) {
          val relTmp = File(destDir, "app-release.apk.tmp")
          src.copyTo(relTmp, overwrite = true)
          relTmp.renameTo(relTarget)
        }
      }
    }
  }
}

tasks.register("autoExportReleaseApkToSystemOutput") {
  dependsOn("assembleRelease")
  val dbgPath = dbgFilePath
  val relPath = relFilePath
  val sysDir = sysOutputDirPath
  val localDir = localOutputDirPath
  val buildDir = buildOutputsDirPath
  doLast {
    val isValidZip = { f: File ->
      if (f.exists() && f.length() > 10_000_000) {
        try {
          val zip = ZipFile(f)
          val hasEntries = zip.entries().hasMoreElements()
          zip.close()
          hasEntries
        } catch (e: Exception) {
          false
        }
      } else {
        false
      }
    }

    val targets = listOf(sysDir, localDir, buildDir)
    val relSrc = File(relPath)
    val dbgSrc = File(dbgPath)
    val srcToUse = if (isValidZip(relSrc)) relSrc else dbgSrc
    if (isValidZip(srcToUse)) {
      targets.forEach { dirPath ->
        val destDir = File(dirPath)
        destDir.mkdirs()
        val targetFile = File(destDir, "app-release.apk")
        val tmpFile = File(destDir, "app-release.apk.tmp")
        srcToUse.copyTo(tmpFile, overwrite = true)
        tmpFile.renameTo(targetFile)
      }
    }
  }
}

tasks.configureEach {
  if (name == "assembleDebug") {
    finalizedBy("autoExportApkToSystemOutput")
  }
  if (name == "assembleRelease") {
    finalizedBy("autoExportReleaseApkToSystemOutput")
  }
}


