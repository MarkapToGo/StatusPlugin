plugins {
    java
    eclipse
    idea
    id("com.gradleup.shadow") version "9.0.0-beta6"
}

group = "de.stylelabor"
version = "7.0.2"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
    // Paper API (1.21+ for Brigadier command support)
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    
    // PlaceholderAPI
    compileOnly("me.clip:placeholderapi:2.11.5")
    
    // TAB API (from JitPack)
    compileOnly("com.github.NEZNAMY:TAB-API:5.0.3")
    
    // LibertyBans API
    compileOnly("space.arim.libertybans:bans-api:1.0.4")
    
    // bStats
    implementation("org.bstats:bstats-bukkit:3.0.2")
    
    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("org.json:json:20231013")
}

tasks {
    processResources {
        filesMatching("paper-plugin.yml") {
            expand(
                "version" to project.version,
                "name" to project.name
            )
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("org.bstats", "de.stylelabor.statusplugin.lib.bstats")
        relocate("okhttp3", "de.stylelabor.statusplugin.lib.okhttp3")
        relocate("okio", "de.stylelabor.statusplugin.lib.okio")
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
        options.compilerArgs.add("-parameters")
    }
}
