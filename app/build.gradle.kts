plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionTagProperty = providers.gradleProperty("kanukaVersionTag").orNull
val githubRefType = providers.environmentVariable("GITHUB_REF_TYPE").orNull
val githubRefName = providers.environmentVariable("GITHUB_REF_NAME").orNull
val candidateVersionTag = when {
    !versionTagProperty.isNullOrBlank() -> versionTagProperty
    githubRefType == "tag" && !githubRefName.isNullOrBlank() -> githubRefName
    else -> null
}
val semVerRegex = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
val semVerMatch = candidateVersionTag?.let { semVerRegex.matchEntire(it) }
val resolvedVersionName = semVerMatch?.let {
    val major = it.groupValues[1]
    val minor = it.groupValues[2]
    val patch = it.groupValues[3]
    val suffix = candidateVersionTag.removePrefix("v").removePrefix("$major.$minor.$patch")
    "$major.$minor.$patch$suffix"
} ?: "1.0"
val resolvedVersionCode = semVerMatch?.let {
    val major = it.groupValues[1].toInt()
    val minor = it.groupValues[2].toInt()
    val patch = it.groupValues[3].toInt()
    (major * 10000) + (minor * 100) + patch
} ?: 1

val releaseStoreFile = providers.gradleProperty("KANUKA_UPLOAD_STORE_FILE")
    .orElse(providers.environmentVariable("KANUKA_UPLOAD_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("KANUKA_UPLOAD_STORE_PASSWORD")
    .orElse(providers.environmentVariable("KANUKA_UPLOAD_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("KANUKA_UPLOAD_KEY_ALIAS")
    .orElse(providers.environmentVariable("KANUKA_UPLOAD_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("KANUKA_UPLOAD_KEY_PASSWORD")
    .orElse(providers.environmentVariable("KANUKA_UPLOAD_KEY_PASSWORD"))
val hasReleaseSigningConfig = listOf(
    releaseStoreFile.orNull,
    releaseStorePassword.orNull,
    releaseKeyAlias.orNull
).all { !it.isNullOrBlank() }

android {
    namespace = "YDKK.kanuka"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "YDKK.kanuka"
        minSdk = 36
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = file(releaseStoreFile.orNull!!)
                storePassword = releaseStorePassword.orNull
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull?.takeIf { it.isNotBlank() }
                    ?: releaseStorePassword.orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation(libs.litertlm.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
