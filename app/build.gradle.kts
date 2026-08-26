import java.io.File
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * La version est pilotée par la propriété Gradle `pdfboxVersionName`
 * (la CI la dérive du tag git : `-PpdfboxVersionName=1.2.3`).
 * Le versionCode en découle mécaniquement : 1.2.3 -> 10203, donc strictement
 * croissant tant que les versions le sont.
 */
val appVersionName: String =
    (providers.gradleProperty("pdfboxVersionName").orNull)
        ?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }
        ?: "1.0.0"

fun versionCodeOf(name: String): Int {
    val parts = name.split("-", "+")[0].split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor in 0..99 && patch in 0..99) {
        "Version $name : minor et patch doivent rester < 100, sinon le versionCode " +
            "cesse d'être strictement croissant."
    }
    return major * 10_000 + minor * 100 + patch
}

/**
 * Signature. Deux cas :
 *  - secrets de CI présents  -> vraie clé privée, jamais dans le dépôt ;
 *  - sinon                   -> clé publique versionnée `keystore/pdfbox-public.jks`.
 *
 * La seconde n'est *pas* un secret : son mot de passe est dans ce fichier. Elle
 * n'existe que pour donner une signature **stable** d'un build à l'autre, sans
 * quoi chaque APK refuserait de s'installer par-dessus le précédent. Elle
 * n'authentifie rien : n'importe qui peut signer un APK avec.
 */
val ciKeystoreB64: String? = providers.environmentVariable("PDFBOX_KEYSTORE_B64")
    .orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.fabrice.pdfbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fabrice.pdfbox"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        resourceConfigurations += setOf("fr", "en")

        // ML Kit embarque des bibliothèques natives : sans filtre, l'APK
        // transporte quatre ABI dont deux ne servent plus (armeabi-v7a reste
        // pour les appareils 32 bits d'Android 8, x86_64 pour l'émulateur).
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("stable") {
            val tmp = System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir") ?: "/tmp"
            if (ciKeystoreB64 != null) {
                val ks = File(tmp, "pdfbox-release.jks")
                val bytes = Base64.getDecoder().decode(ciKeystoreB64)
                if (!ks.exists() || ks.length() != bytes.size.toLong()) ks.writeBytes(bytes)
                storeFile = ks
                storePassword = System.getenv("PDFBOX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PDFBOX_KEY_ALIAS")
                keyPassword = System.getenv("PDFBOX_KEY_PASSWORD")
            } else {
                storeFile = file("keystore/pdfbox-public.jks")
                storePassword = "pdfbox"
                keyAlias = "pdfbox"
                keyPassword = "pdfbox"
            }
        }
    }

    buildTypes {
        debug {
            // Pas de suffixe d'applicationId : les raccourcis statiques
            // (res/xml/shortcuts.xml) ciblent un paquet en dur, que les
            // ressources ne savent pas paramétrer.
            versionNameSuffix = "-debug"
        }
        release {
            // R8 est désactivé en v1 : PDFBox-Android charge ses ressources et
            // ses filtres de flux par réflexion, et un `keep` incomplet ne se
            // voit qu'à l'exécution, sur un PDF particulier. Le gain de taille
            // ne vaut pas ce risque tant que la couverture de test est manuelle.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        checkDependencies = false
        htmlReport = true
        // Sans cela, la console n'affiche que la *première* erreur : corriger
        // un rapport de dix défauts demanderait dix builds.
        textReport = true
        // La bibliothèque ML Kit publie des versions plus récentes que celles
        // épinglées ici ; la mise à jour est le travail de Dependabot, pas une
        // raison de faire échouer le build.
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Utilisé par la CI pour nommer l'APK sans dupliquer la logique de version. */
tasks.register("printVersionName") {
    val version = appVersionName
    doLast { println(version) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
