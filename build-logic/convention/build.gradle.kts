plugins {
    `kotlin-dsl`
}
group = "me.rerere.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "rikkahub.android.library"
            implementationClass = "me.rerere.convention.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "rikkahub.android.library.compose"
            implementationClass = "me.rerere.convention.AndroidLibraryComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "rikkahub.android.application"
            implementationClass = "me.rerere.convention.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "rikkahub.android.application.compose"
            implementationClass = "me.rerere.convention.AndroidApplicationComposeConventionPlugin"
        }
    }
}
