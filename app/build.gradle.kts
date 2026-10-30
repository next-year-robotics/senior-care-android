import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 지도 키는 local.properties에서 읽는다(저장소에 없음). 없으면 "dummy"로 빌드된다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String) = (localProps.getProperty(name) ?: System.getenv(name) ?: "dummy").trim()

android {
    namespace = "com.nextyearrobotics.seniorcare"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.nextyearrobotics.seniorcare"
        // 지원 기기는 Galaxy S26 Ultra 한 대(Android 16). 결정 기록: mobile-robot docs/senior-care/architecture.md §7
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"${localProp("KAKAO_NATIVE_APP_KEY")}\"")
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${localProp("KAKAO_REST_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_BRIDGE_URL", "\"ws://mobile-robot.local:8765/bridge/v1\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
    sourceSets.getByName("main").resources.srcDir(rootProject.file("contracts/phone-bridge/v1/schema"))
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kakao.map)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

// External mock selection is part of the test input; switching scenarios must rerun tests.
tasks.withType<Test>().configureEach {
    listOf("BRIDGE_TEST_URL", "SOS_TEST_URL", "SOS_TEST_CLEAR", "MOCK_MATRIX_BASE_PORT").forEach { key ->
        val value = providers.environmentVariable(key).orElse("")
        inputs.property(key, value)
        environment(key, value.get())
    }
}
