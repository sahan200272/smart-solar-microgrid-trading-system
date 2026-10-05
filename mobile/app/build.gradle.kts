import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Read local.properties file to get the Maps API key safely,
// without hard-coding it into a file that gets committed to Git
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// Finds this PC's Wi-Fi/LAN IPv4 address so a physical phone on the same network can reach the API.
// A ValueSource is re-checked on every build, so a new IP from the router is picked up automatically.
abstract class LanIpValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String? {
        val virtualAdapter = Regex("virtual|vmware|vbox|hyper-v|vethernet|wsl|docker", RegexOption.IGNORE_CASE)
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !virtualAdapter.containsMatchIn(it.displayName.orEmpty()) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .sortedByDescending { it.startsWith("192.168.") }
            .firstOrNull()
    }
}

// Set API_BASE_URL in local.properties to override the detected address (e.g. http://10.0.2.2:5098/)
val apiBaseUrl = (localProperties.getProperty("API_BASE_URL")
    ?: "http://${providers.of(LanIpValueSource::class.java) {}.orNull ?: "10.0.2.2"}:5098")
    .trimEnd('/') + "/"
logger.lifecycle("Mobile API base URL: $apiBaseUrl")

android {
    namespace = "com.example.microgridsystem"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.microgridsystem"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injects the Maps API key from local.properties into the manifest
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:20.0.0")

    // For making API calls to your Web API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Pull to refresh support
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Pure Java QR Code generation (ZXing Core encoder)
    implementation("com.google.zxing:core:3.5.3")

    // Booking Views & Grid Operator Verification: camera QR scanning for Operator Mode.
    // ZXing Android Embedded (Apache 2.0) - https://github.com/journeyapps/zxing-android-embedded
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}