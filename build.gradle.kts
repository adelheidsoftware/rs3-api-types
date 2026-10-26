import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// The generator of the stubs in generated/: Java 25, with JSpecify, Error Prone and NullAway, Checkstyle and JUnit, as
// in Oneiro. `./gradlew generate` reads the docs site, from the cache in cache/ where it has the pages, and the CSVs in
// generated/constants/, and rewrites the LuaCATS; `-Prefresh` fetches every page again, and `-Poffline` reads the cache
// alone. `./gradlew constants` reads the constants dump from the beta client's plugin database into
// generated/constants/, then generates; `-Pdatabase=<path>` reads another database.

plugins {
	java
	checkstyle
	alias(libs.plugins.errorprone)
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

dependencies {
	// Every package is @NullMarked, its nullable types @Nullable
	implementation(libs.jspecify)
	implementation(libs.jsoup)
	// The beta client's plugin database, which the constants dump writes to, is SQLite
	runtimeOnly(libs.sqlite.jdbc)
	errorprone(libs.errorprone.core)
	errorprone(libs.nullaway)
	testImplementation(platform(libs.junit.bom))
	testImplementation(libs.junit.jupiter)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.errorprone {
		// NullAway turns a nullness mistake into a compile error
		check("NullAway", CheckSeverity.ERROR)
		option("NullAway:OnlyNullMarked", "true")
		option("NullAway:JSpecifyMode", "true")
		check("RemoveUnusedImports", CheckSeverity.ERROR)
	}
}

tasks.withType<Javadoc>().configureEach {
	(options as StandardJavadocDocletOptions).apply {
		encoding = "UTF-8"
		// Every check but the one that wants @param and @return where the description already says it; a warning fails
		// the build
		addBooleanOption("Xdoclint:all,-missing", true)
		addBooleanOption("Werror", true)
		quiet()
	}
}

// The layout rules in config/checkstyle; a violation fails the build
checkstyle {
	toolVersion = libs.versions.checkstyle.get()
	maxWarnings = 0
}

tasks.test {
	useJUnitPlatform()
}

val generate = tasks.register<JavaExec>("generate") {
	description = "Generates generated/ from the docs site and the constants, fetching the pages the cache lacks."
	group = "generation"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "org.adelheid.rs3types.Generate"
	workingDir = projectDir
	args = buildList {
		if (providers.gradleProperty("refresh").isPresent) add("--refresh")
		if (providers.gradleProperty("offline").isPresent) add("--offline")
	}
}

tasks.register<JavaExec>("constants") {
	description = "Reads the constants dump from the beta client's plugin database, then generates."
	group = "generation"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "org.adelheid.rs3types.Constants"
	// The SQLite driver loads its native library, which Java warns about unless native access is allowed
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	workingDir = projectDir
	args = listOfNotNull(providers.gradleProperty("database").orNull)
	finalizedBy(generate)
}
