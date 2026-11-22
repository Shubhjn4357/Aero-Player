// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

val copyBuildToOutput by tasks.registering(Copy::class) {
  group = "build"
  description = "Copies build outputs (.build and app build APK/AAB artifacts) into the /output directory."

  val rootDirLayout = layout.projectDirectory
  into(rootDirLayout.dir("output"))

  // Copy from .build if present
  from(rootDirLayout.dir(".build")) {
    into("dot-build")
  }
  // Copy from app/.build if present
  from(rootDirLayout.dir("app/.build")) {
    into("app-dot-build")
  }
  // Copy APK & AAB build outputs so every assembled artifact lands cleanly in /output
  from(rootDirLayout.dir("app/build/outputs/apk")) {
    into("apk")
  }
  from(rootDirLayout.dir("app/build/outputs/bundle")) {
    into("bundle")
  }

  includeEmptyDirs = false
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

subprojects {
  tasks.configureEach {
    if (name == "assembleDebug" || name == "assembleRelease" ||
        name == "bundleDebug" || name == "bundleRelease") {
      finalizedBy(copyBuildToOutput)
    }
  }
}

