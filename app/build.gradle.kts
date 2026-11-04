import java.util.Properties

// app 模块构建脚本：Compose + OkHttp + kotlinx.serialization
// ⚠ AGP 9.x 起 Kotlin 编译内置，**不再写 id("org.jetbrains.kotlin.android")**（写了会冲突）
plugins {
    id("com.android.application")
    // Kotlin 2.0 起 Compose 编译器是独立插件，必须和内置 Kotlin 版本同一线（2.2.10）
    id("org.jetbrains.kotlin.plugin.compose")
    // ⚠ 这个插件和下面 kotlinx-serialization-json 依赖必须成对出现，缺一个 @Serializable 就编译不过
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 后端地址：默认从 local.properties 的 `backendUrl` 读，读不到就用模拟器默认值。
 * 走 local.properties 而不是 build.gradle.kts 里的硬编码字符串，好处是改地址不用动受版本控制的文件。
 *
 * local.properties（不进 git，机器相关）里可以写：
 *   backendUrl=http://192.168.1.8:3201/
 * ⚠ 末尾必须带 "/"，OkHttp 拼相对路径靠它。
 */
fun readBackendUrl(): String {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { props.load(it) }
    }
    val url = props.getProperty("backendUrl")?.trim().orEmpty()
    return if (url.isEmpty()) "http://10.0.2.2:3000/" else url
}

android {
    namespace = "me.footprint.app"
    // 对齐本机 SDK：只有 android-37.0，没有 35
    compileSdk = 37

    defaultConfig {
        applicationId = "me.footprint.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-M1"

        // 代码里用 BuildConfig.BASE_URL 读
        buildConfigField("String", "BASE_URL", "\"${readBackendUrl()}\"")
    }

    buildTypes {
        debug {
            // debug 包允许明文 HTTP（配 src/debug/AndroidManifest.xml 里的 usesCleartextTraffic）
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // release 必须走 HTTPS，所以这里不放行明文
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// AGP 9 内置 Kotlin 后，JVM 目标用 kotlin { compilerOptions }（旧的 kotlinOptions 已废弃）
// ⚠ 这个块必须在 android { } 外面（顶层），写进 android 里会报找不到方法
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 网络：只要 OkHttp + kotlinx.serialization，不引 Retrofit（接口就 3 个）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
