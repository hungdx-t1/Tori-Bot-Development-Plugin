plugins {
    id("java")
    // Use the Shadow Plugin to create Fat-Jars, similar to maven-shade-plugin
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "{{GROUP_ID}}"
version = "{{VERSION}}"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

val toriVersion = "{{TORI_VERSION}}"
val useDaveProtocol = {{USE_DAVE}}   // toggle DAVE Protocol (requires Java 25)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (useDaveProtocol) 25 else 21))
    }
}

dependencies {
    compileOnly("com.github.hungdx-t1.Tori:tori-api:$toriVersion")
    if (useDaveProtocol) {
        compileOnly("com.github.hungdx-t1.Tori:tori-extension-dave:$toriVersion")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Jar> {
    archiveFileName.set("{{NAME}}.jar")
}

tasks.shadowJar {
    archiveClassifier.set("")

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/MANIFEST.MF")
    exclude("module-info.class")
    exclude("META-INF/versions/**/module-info.class")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
