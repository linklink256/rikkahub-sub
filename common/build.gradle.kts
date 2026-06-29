plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "me.rerere.common"
    defaultConfig { minSdk = 26 }
}
dependencies {
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.okhttp.logging)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
    api(libs.floatingx)
    api(libs.floatingx.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    api(libs.quickjs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
