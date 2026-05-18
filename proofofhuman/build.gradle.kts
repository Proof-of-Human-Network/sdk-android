import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    signing
}

group   = "ge.proofofhuman"
version = "1.0.0"

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
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

tasks.test {
    useJUnitPlatform()
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
