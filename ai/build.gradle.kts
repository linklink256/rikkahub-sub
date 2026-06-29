plugins {
    id("rikkahub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "me.rerere.ai"
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.okhttp.logging)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
