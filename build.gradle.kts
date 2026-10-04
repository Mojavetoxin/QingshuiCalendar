// 清水日历 - 根构建文件
// 插件统一在 gradle/libs.versions.toml 中声明版本
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
