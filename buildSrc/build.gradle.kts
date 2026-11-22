plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("michiVersioning") {
            id = "michi.versioning"
            implementationClass = "michi.versioning.VersioningPlugin"
        }
    }
}
