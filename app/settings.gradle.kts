pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://devrepo.kakao.com/nexus/content/groups/public/") } }
rootProject.name = "eudero-gano-app"
include(":app", ":map-lib", ":health-connect")
project(":health-connect").projectDir = file("../android/health-connect")
