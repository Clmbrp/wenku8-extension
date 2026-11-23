import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.jsoup)
    testImplementation(libs.okhttp.core)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.json)
    testImplementation(libs.tachiyomi.lib.v16)
}

// The source processor generates the extension entry point for main, not unit tests.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

keiyoushi {
    name = "轻小说文库"
    versionCode = 7
    // Provisional until the site's content has been assessed.
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "轻小说文库"
        lang = "zh"
        id = 845_240_625_336_071_213L
        baseUrl = "https://www.wenku8.net"
    }
}
