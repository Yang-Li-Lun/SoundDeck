plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.sounddeck"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.sounddeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") { storeFile = rootProject.file(".local/debug.keystore") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Keep this development signing key in the project, without touching the user's global debug key.
val generateDebugKey by tasks.registering(Exec::class) {
    val key = rootProject.file(".local/debug.keystore")
    onlyIf { !key.exists() }
    doFirst { key.parentFile.mkdirs() }
    commandLine("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-keystore", key.absolutePath,
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey", "-dname",
        "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
}
tasks.matching { it.name == "validateSigningDebug" }.configureEach { dependsOn(generateDebugKey) }


