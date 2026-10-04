// A separate build on purpose. The JReleaser Gradle plugin and the Android Gradle Plugin both bundle JAXB and break each
// other when they share a classpath ("NoClassDefFoundError: javax/activation/DataSource ... RuntimeBuiltinLeafInfoImpl"),
// so JReleaser lives here, away from the library build. It reads the shared values from ../gradle.properties.
rootProject.name = "better-auth-kotlin-client-release"
