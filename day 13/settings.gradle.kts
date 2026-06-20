val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    java.util.Properties().apply {
        localPropertiesFile.inputStream().use { load(it) }
        getProperty("org.gradle.java.home")?.let { path ->
            System.setProperty("org.gradle.java.home", path)
        }
    }
}

rootProject.name = "day-13"
