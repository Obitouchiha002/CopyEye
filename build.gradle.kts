// Root build file. Plugins are put on the build classpath here with a single, catalog-controlled
// version; the `:app` module opts in.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
