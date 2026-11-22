import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "轻小说文库"
    versionCode = 1
    // Provisional until the site's content has been assessed.
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "轻小说文库"
        lang = "zh"
        id = 845_240_625_336_071_213L
        // Placeholder only; verify the real site and its domains in stage 1.
        baseUrl = "https://wenku8.invalid"
    }
}
