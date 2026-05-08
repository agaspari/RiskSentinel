plugins {
    java
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(26)
        }
    }

    repositories {
        mavenCentral()
    }
}
