import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing（v2.5.1 开源化改造，与公开仓库一致）：
 * 凭据从环境变量（SP_STORE_PASSWORD/SP_KEY_ALIAS/SP_KEY_PASSWORD）
 * 或 local.properties（sp.store.password/sp.key.alias/sp.key.password）读取，
 * 明文不再入库；无 jks 时回退 debug 签名。
 */
val releaseKeystoreFile = rootProject.file("swiftplayer-release.jks")
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.swiftplayer"
    compileSdk = 35

    signingConfigs {
        if (releaseKeystoreFile.exists()) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("SP_STORE_PASSWORD")
                    ?: signingProps.getProperty("sp.store.password") ?: ""
                keyAlias = System.getenv("SP_KEY_ALIAS")
                    ?: signingProps.getProperty("sp.key.alias") ?: "swiftplayer"
                keyPassword = System.getenv("SP_KEY_PASSWORD")
                    ?: signingProps.getProperty("sp.key.password") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.swiftplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "2.5.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystoreFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Media3 (ExoPlayer)
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // Networking (Emby)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB/NAS
    implementation("com.hierynomus:smbj:0.13.0")

    // LAN Discovery (mDNS/DNS-SD)
    implementation("org.jmdns:jmdns:3.5.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ViewPager2 (for tab navigation)
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Desugaring (for java.nio on older APIs)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
