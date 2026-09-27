// AGP 9 built-in Kotlin: org.jetbrains.kotlin.android 플러그인은 적용하지 않는다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
