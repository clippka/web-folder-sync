import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
}

group = "app.foldersync"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

// ---- Frontend: npm ci -> tsc type check -> esbuild bundle -> packaged as static assets ----
val frontendDir = layout.projectDirectory.dir("frontend")
val frontendOut = layout.buildDirectory.dir("generated/frontend/static/app")
val npm = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"

val npmCi by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val frontendTypecheck by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir = frontendDir.asFile
    commandLine(npm, "run", "typecheck")
    inputs.files(fileTree(frontendDir.dir("src")), frontendDir.file("tsconfig.json"), frontendDir.file("package-lock.json"))
    outputs.upToDateWhen { true }
}

val frontendBundle by tasks.registering(Exec::class) {
    dependsOn(frontendTypecheck)
    workingDir = frontendDir.asFile
    commandLine(npm, "run", "build", "--", frontendOut.get().asFile.absolutePath)
    inputs.files(fileTree(frontendDir.dir("src")), frontendDir.file("build.mjs"), frontendDir.file("package-lock.json"))
    outputs.dir(frontendOut)
}

val frontendTest by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir = frontendDir.asFile
    commandLine(npm, "test")
    inputs.files(fileTree(frontendDir.dir("src")), fileTree(frontendDir.dir("test")), frontendDir.file("package-lock.json"))
    outputs.upToDateWhen { true }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/frontend"))
}

tasks.processResources { dependsOn(frontendBundle) }

tasks.test {
    dependsOn(frontendTest)
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
