plugins {
    java
}

group = "com.risksentinel"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
