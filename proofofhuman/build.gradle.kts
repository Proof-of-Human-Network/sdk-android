import com.vanniktech.maven.publish.SonatypeHost
import java.util.Base64

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    signing
}

group   = "ge.proofofhuman"
version = "1.5.0"

// ── Dependencies ───────────────────────────────────────────────────────────────

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// ── GPG signing ────────────────────────────────────────────────────────────────
// Reads signing.key (base64-encoded ASCII armor) + signing.password from
// gradle.properties or ~/.gradle/gradle.properties.

signing {
    val rawKey  = project.findProperty("signing.key")      as String?
    val pass    = project.findProperty("signing.password") as String?
    if (rawKey != null && pass != null) {
        val key = if (rawKey.trimStart().startsWith("-----")) rawKey
                  else String(Base64.getDecoder().decode(rawKey.trim()))
        useInMemoryPgpKeys(key, pass)
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("ge.proofofhuman", "proofofhuman", version.toString())

    pom {
        name.set("proofofhuman")
        description.set("Android / JVM SDK for the Proof of Human API — scan wallet addresses for human identity signals.")
        url.set("https://github.com/Proof-of-Human-Network/sdk-android")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("proofofhuman")
                name.set("Proof of Human")
                email.set("support@assetux.com")
                url.set("https://github.com/Proof-of-Human-Network")
            }
        }

        scm {
            url.set("https://github.com/Proof-of-Human-Network/sdk-android")
            connection.set("scm:git:git://github.com/proofofhuman/proofofhuman-android.git")
            developerConnection.set("scm:git:ssh://github.com/proofofhuman/proofofhuman-android.git")
        }
    }
}
