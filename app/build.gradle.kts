plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "com.example.safetyway"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.safetyway"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            // 이 설정을 추가하면 라이브러리를 압축하지 않고 설치합니다.
            // 16 KB 정렬 문제를 우회하는 데 도움이 됩니다.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    val roomversion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomversion")
    implementation("androidx.room:room-ktx:$roomversion")
    ksp("androidx.room:room-compiler:$roomversion")
    // 1. ConstraintLayout 부품 추가
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // 2. Fragment 부품 추가 (MapFragment를 쓰기 위해 필수!)
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // 네이버 지도 SDK가 있는지 다시 한 번 확인!
    implementation("com.naver.maps:map-sdk:3.23.1")

    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.9.2")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.11.0")

}