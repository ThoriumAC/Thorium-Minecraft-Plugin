import com.google.protobuf.gradle.proto

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("com.google.protobuf") version "0.10.0"
}

group = "ac.thorium"
version = "0.2.4"

val protobufVersion = "4.36.1"
val packetEventsVersion = "2.13.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigot" }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "sonatype" }
    maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc" }
}

dependencies {
    // 1.8.8's transitive bungeecord-chat:1.8-SNAPSHOT no longer resolves; pull the API alone plus what its classes reference.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("com.google.guava:guava:17.0")
    compileOnly("org.yaml:snakeyaml:1.15")
    compileOnly("com.google.code.gson:gson:2.2.4")
    implementation("com.github.retrooper:packetevents-spigot:$packetEventsVersion")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")   // silences Java-WebSocket's SLF4J "no providers" stderr warning
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")

    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    testImplementation("com.google.guava:guava:17.0")
    testImplementation("org.yaml:snakeyaml:1.15")
    testImplementation("com.google.code.gson:gson:2.2.4")
    testImplementation("commons-lang:commons-lang:2.6")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:deprecation"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

sourceSets {
    main {
        proto { srcDir("proto") }
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("thorium-minecraft")
    val prefix = "ac.thorium.mc.libs"
    relocate("com.github.retrooper.packetevents", "$prefix.packetevents.api")
    relocate("io.github.retrooper.packetevents", "$prefix.packetevents.impl")
    relocate("net.kyori", "$prefix.kyori")
    relocate("org.java_websocket", "$prefix.websocket")
    relocate("org.slf4j", "$prefix.slf4j")
    relocate("com.google.protobuf", "$prefix.protobuf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
}

tasks.jar { archiveClassifier.set("plain") }
tasks.build { dependsOn(tasks.shadowJar) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
