pluginManagement {
    repositories {
        // JitPack's build machines are regularly rate limited by Maven Central (HTTP 429), so prefer a mirror.
        maven(url = "https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "preprocessor"
