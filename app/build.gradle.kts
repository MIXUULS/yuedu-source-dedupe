import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名密码从 local.properties 读取（该文件已被 .gitignore 排除，不会提交到仓库）；
// 未配置时回退默认值，保证开箱即可本地构建。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, def: String): String = localProps.getProperty(name, def)
android {
    namespace = "com.mina.yuedu"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mina.yuedu"
        minSdk = 24
        targetSdk = 35
        versionCode = 304
        versionName = "3.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("debugKey") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("releaseKey") {
            storeFile = file("../keystore/release.keystore")
            storePassword = localProp("releaseStorePassword", "yuedu2026")
            keyAlias = localProp("releaseKeyAlias", "yuedu")
            keyPassword = localProp("releaseKeyPassword", "yuedu2026")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugKey")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("releaseKey")
            // 开源项目无需混淆/资源收缩：保持可读堆栈便于排查，源码公开也没有加密意义
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
}
