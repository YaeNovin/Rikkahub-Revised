plugins {
    id("rikkahub.android.library.compose")
}

android {
    namespace = "me.rerere.material3"
    sourceSets {
        named("main") {
            // Keep one Android build source of truth. Other language folders
            // mirror upstream MCU implementations for reference only.
            kotlin.srcDir("material-color-utilities/kotlin")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
}
