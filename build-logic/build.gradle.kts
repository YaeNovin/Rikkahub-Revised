plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("workspaceTermux") {
            id = "rikkahub.workspace-termux"
            implementationClass = "WorkspaceTermuxPlugin"
        }
        register("androidLibrary") {
            id = "rikkahub.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "rikkahub.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
    }
}
