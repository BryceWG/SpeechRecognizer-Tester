plugins {
    // 使用与主项目相同版本，方便在同一开发环境中构建
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

