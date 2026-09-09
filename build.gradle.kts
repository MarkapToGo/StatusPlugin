plugins {
    java
    eclipse
    idea
    id("com.gradleup.shadow") version "9.4.1"
}

group = "de.stylelabor"
version = "7.1.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
    // LibertyBans and arim parent
    maven("https://mvn-repo.arim.space/lesser-gpl3/")
    maven("https://mvn-repo.arim.space/affero-gpl3/")
}

dependencies {
    // Paper API (26.2+ for modern support)
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    
    // PlaceholderAPI
    compileOnly("me.clip:placeholderapi:2.11.5")
    
    // TAB API (from JitPack)
    compileOnly("com.github.NEZNAMY:TAB-API:5.0.3")
    
    // LibertyBans API
    compileOnly("space.arim.libertybans:bans-api:1.0.4")
    
    // bStats
    implementation("org.bstats:bstats-bukkit:3.0.2")
    
    // JSON parsing
    implementation("org.json:json:20231013")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
}

tasks {
    test {
        useJUnitPlatform()
    }
    processResources {
        val props = mapOf(
            "version" to project.version,
            "name" to project.name
        )
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("org.bstats", "de.stylelabor.statusplugin.lib.bstats")
        relocate("org.json", "de.stylelabor.statusplugin.lib.json")
        
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }

    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }
}
