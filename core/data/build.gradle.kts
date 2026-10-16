plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    namespace = "com.rtbishop.look4sat.core.data"
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
