plugins {
    id("rikkahub.android.library.compose")
}

android {
    namespace = "me.rerere.highlight"

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
