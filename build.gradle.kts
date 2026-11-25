// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

val copyBuildToOutput by tasks.registering(Sync::class) {
  group = "build"
  description = "Manually syncs local Gradle APK/AAB build outputs into /output (for local builds only; AI Studio exports APKs via Settings)."

  val rootDirLayout = layout.projectDirectory
  into(rootDirLayout.dir("output"))

  from(rootDirLayout.dir("app/build/outputs/apk")) {
    into("apk")
  }
  from(rootDirLayout.dir("app/build/outputs/bundle")) {
    into("bundle")
  }

  includeEmptyDirs = false
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

