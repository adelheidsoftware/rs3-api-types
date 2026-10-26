import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// The generator of the stubs in library/: Java 25, with JSpecify, Error Prone and NullAway, Checkstyle and JUnit, as in
// Oneiro. `./gradlew generate` reads the docs site, from the cache in cache/ where it has the pages, and rewrites
// library/; `-Prefresh` fetches every page again, and `-Poffline` reads the cache alone.

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

tasks.register<JavaExec>("generate") {
	description = "Generates library/ from the docs site, fetching the pages the cache lacks."
	group = "generation"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "org.adelheid.rs3types.Generate"
	workingDir = projectDir
	args = buildList {
		if (providers.gradleProperty("refresh").isPresent) add("--refresh")
		if (providers.gradleProperty("offline").isPresent) add("--offline")
	}
}
