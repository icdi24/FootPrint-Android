// 根构建脚本：只声明插件版本，不 apply（apply 在 app/build.gradle.kts 里做）。
//
// 版本组合（2026-11 对齐本机 Android Studio 2026.2.2 的实查结果，不要随意拆开改）：
//   AGP      9.4.1  —— Studio 自带 AGP 9.5.0，9.4.1 是其稳定版；支持最高 API 37.0
//   Gradle   9.6.0  —— ⚠ 实测：AGP 9.4.1 要求 Gradle >= 9.6.0（网上流传的 9.3.1 是错的）
//   JDK      17     —— AGP 9.x 要求 JDK 17+（Studio 自带 JBR 25 也可以）
//
// ⚠ AGP 9.0 起有两处颠覆性默认变更，本工程已按新规写：
//   1) android.builtInKotlin=true —— Kotlin 编译内置到 AGP，**不再手写 kotlin-android 插件**
//   2) android.newDsl=true        —— 旧 applicationVariants 等 API 不可用
// 所以这里只留三个插件：android、compose 编译器、serialization。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
