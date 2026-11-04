// 工程根设置：仓库源、插件版本、模块清单。
// 组件版本集中在这里（pluginManagement），app 模块里只写 `alias(...)` 或裸 id，方便以后统一升级。
pluginManagement {
    repositories {
        // 不再用 content { includeGroupByRegex } 过滤：AGP 9 的插件依赖跨越更多 group，
        // 过滤太窄会报 "Could not find com.xxx" 这类难查的错。仓库顺序已保证正确性。
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "footprint-android"
include(":app")
