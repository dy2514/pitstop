@Suppress("UNCHECKED_CAST")
try {
    val peClass = Class.forName("java.lang.ProcessEnvironment")
    val fields = arrayOf("theEnvironment", "theCaseInsensitiveEnvironment")
    for (fieldName in fields) {
        try {
            val field = peClass.getDeclaredField(fieldName)
            field.isAccessible = true
            (field.get(null) as? MutableMap<String, String>)?.remove("ANDROID_PREFS_ROOT")
        } catch (_: Throwable) {}
    }
} catch (_: Throwable) {}

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); mavenCentral(); maven("https://nexus-playground.pleos.ai/repository/maven-releases/") }
}
rootProject.name = "PitStop"
include(":pitstop-core", ":pitstop")
