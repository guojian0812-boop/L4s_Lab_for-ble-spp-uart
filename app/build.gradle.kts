plugins {
    alias(libs.plugins.convention.applicationPlugin)
}


android {
    // Application ID is isolated from the official Look4Sat installation.
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".labs" }
        getByName("release") { applicationIdSuffix = ".labs" }
    }
}
