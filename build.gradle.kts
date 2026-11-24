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
  description = "Syncs fresh build outputs (APK/AAB artifacts) into the /output directory, removing any stale files."

  val rootDirLayout = layout.projectDirectory
  into(rootDirLayout.dir("output"))

  // Copy APK & AAB build outputs so every assembled artifact lands cleanly in /output
  from(rootDirLayout.dir("app/build/outputs/apk")) {
    into("apk")
    rename { fileName ->
      if (fileName == "app-debug.apk") "AeroPlayer-v1.4.7-arm64-v8a-debug.apk" else fileName
    }
  }
  from(rootDirLayout.dir("app/build/outputs/bundle")) {
    into("bundle")
  }

  includeEmptyDirs = false
  duplicatesStrategy = DuplicatesStrategy.INCLUDE

  doLast {
    val rootDirFile = rootDirLayout.asFile
    val largeFiles = rootDirFile.walkTopDown()
      .onEnter { it.name != ".gradle" && it.name != ".kotlin" && it.name != ".git" && it.name != "intermediates" }
      .filter { it.isFile && it.length() >= 10L * 1024L * 1024L }
      .toList()
    largeFiles.forEach { f ->
      val sizeMb = String.format(java.util.Locale.US, "%.2f", f.length() / (1024.0 * 1024.0))
      val modified = java.time.Instant.ofEpochMilli(f.lastModified()).toString()
      if (f.extension == "apk") {
        var uncompressedBytes = 0L
        val abis = sortedSetOf<String>()
        val foundMarkers = linkedSetOf<String>()
        val requiredMarkers = listOf("AeroVpnService", "drawer_inbuilt_vpn_item", "drawer_torrent_vpn_item", "1.4.7")
        java.util.zip.ZipFile(f).use { zip ->
          zip.entries().asSequence().forEach { entry ->
            if (entry.size > 0) uncompressedBytes += entry.size
            if (entry.name.startsWith("lib/") && entry.name.count { c -> c == '/' } >= 2) {
              abis.add(entry.name.substringAfter("lib/").substringBefore("/"))
            }
            if (entry.name.startsWith("classes") && entry.name.endsWith(".dex") && foundMarkers.size < requiredMarkers.size) {
              val dexBytes = zip.getInputStream(entry).readBytes()
              val dexAscii = String(dexBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
              requiredMarkers.forEach { marker ->
                if (dexAscii.contains(marker)) foundMarkers.add(marker)
              }
            }
          }
        }
        val uncompMb = String.format(java.util.Locale.US, "%.2f", uncompressedBytes / (1024.0 * 1024.0))
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").let { md ->
          f.inputStream().use { inp ->
            val buf = ByteArray(8192)
            while (true) {
              val n = inp.read(buf)
              if (n <= 0) break
              md.update(buf, 0, n)
            }
          }
          md.digest().joinToString("") { "%02x".format(it) }.take(12)
        }
        val relPath = f.relativeTo(rootDirFile).path
        logger.lifecycle("[copyBuildToOutput] APK: $relPath | fileSize=${sizeMb} MB | uncompressed=${uncompMb} MB | sha256=$sha256 | modified=$modified | ABIs=$abis | markers=$foundMarkers")
        if (relPath.startsWith("output/")) {
          if (abis != setOf("arm64-v8a")) {
            throw org.gradle.api.GradleException("Unexpected ABIs in $relPath: $abis (expected only [arm64-v8a])")
          }
          if (f.length() > 80L * 1024L * 1024L) {
            throw org.gradle.api.GradleException("Output APK $relPath is unexpectedly large: ${sizeMb} MB")
          }
          val missing = requiredMarkers - foundMarkers
          if (missing.isNotEmpty()) {
            throw org.gradle.api.GradleException("Output APK $relPath is missing expected v1.4.7 DEX markers: $missing")
          }
        }
      } else {
        logger.lifecycle("[copyBuildToOutput] Large file: ${f.relativeTo(rootDirFile).path} | ${sizeMb} MB | modified=$modified")
      }
    }
  }
}

subprojects {
  tasks.configureEach {
    if (name == "assembleDebug" || name == "assembleRelease" ||
        name == "bundleDebug" || name == "bundleRelease") {
      finalizedBy(copyBuildToOutput)
    }
  }
}

