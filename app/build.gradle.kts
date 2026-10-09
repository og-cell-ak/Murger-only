plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ogcellak.murgeronly"; compileSdk=35
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 defaultConfig { applicationId="com.ogcellak.murgeronly"; minSdk=26; targetSdk=35; versionCode=1; versionName="1.0" }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"); implementation("androidx.media3:media3-transformer:1.5.1"); implementation("androidx.media3:media3-effect:1.5.1"); implementation("androidx.media3:media3-common:1.5.1") }
