plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.marineradar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.marineradar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Google Maps API-nyckel: läses från (i prioritetsordning)
        // 1) miljövariabeln MAPS_API_KEY (används av GitHub Actions, se
        //    .github/workflows/build-apk.yml – lägg till en repo-secret
        //    med samma namn)
        // 2) local.properties (för lokala Android Studio-byggen, lägg
        //    till raden MAPS_API_KEY=din-nyckel-här – filen är redan
        //    gitignorad som standard av Android Studio)
        // Saknas nyckeln byggs appen ändå (bara Google Maps-kartan visas
        // inte) – OpenStreetMap-alternativet kräver ingen nyckel alls.
        val localProps = java.util.Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) load(f.inputStream())
        }
        val mapsApiKey = System.getenv("MAPS_API_KEY")
            ?: localProps.getProperty("MAPS_API_KEY")
            ?: ""
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Maps – för kartöverlägg med radarbilden ovanpå
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")

    // OpenStreetMap via osmdroid – helt gratis alternativ, ingen API-nyckel behövs
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
