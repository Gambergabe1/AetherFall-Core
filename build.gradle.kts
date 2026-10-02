plugins {
    java
}

group = "gg.aetherfall"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc:paper-api:26.2.build.129-stable")
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    // PlaceholderAPI is optional at runtime; compile against the jar shipped in the server folder.
    compileOnly(files("../server/plugins/placeholderapi.jar"))
    compileOnly(files("../server/plugins/essentialsx.jar"))
    compileOnly(files("../server/plugins/vaultunlocked.jar"))
    compileOnly(files("../server/plugins/worldguard.jar", "../server/plugins/worldedit.jar"))
    compileOnly(files("../server/plugins/nuvotifier.jar"))
    compileOnly(files("../server/plugins/griefprevention.jar"))
    compileOnly(files("../server/plugins/floodgate.jar"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// `gradlew deploy` builds and copies the jar straight into the server's plugins folder.
tasks.register<Copy>("deploy") {
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into("../server/plugins")
    rename { "AetherCore.jar" }
}





























