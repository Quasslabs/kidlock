import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Values come from the environment, or from local.properties (never committed):
//   KIDLOCK_KEYSTORE / kidlock.keystore                    path to the .jks file
//   KIDLOCK_KEYSTORE_PASSWORD / kidlock.keystore.password  store and key password
//   KIDLOCK_KEY_ALIAS / kidlock.key.alias                  key alias (defaults to "kidlock")
// Without them, release builds come out unsigned and debug builds are unaffected.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("KIDLOCK_KEYSTORE", "kidlock.keystore")
val releasePassword = signingValue("KIDLOCK_KEYSTORE_PASSWORD", "kidlock.keystore.password")
val releaseAlias = signingValue("KIDLOCK_KEY_ALIAS", "kidlock.key.alias") ?: "kidlock"
if ((releaseKeystore == null) != (releasePassword == null)) {
    logger.warn("KidLock: only one of the keystore path and password is set, so the release build will be unsigned.")
}

android {
    namespace = "com.quasslabs.kidlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quasslabs.kidlock"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
