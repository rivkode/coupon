plugins {
	id("org.springframework.boot") version "3.5.14" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
	group = "com.promotion"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}
}

subprojects {
	apply(plugin = "java")
	apply(plugin = "io.spring.dependency-management")

	configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(21)
		}
	}

	configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
		imports {
			mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
		}
	}

	dependencies {
		"compileOnly"("org.projectlombok:lombok")
		"annotationProcessor"("org.projectlombok:lombok")
		"testCompileOnly"("org.projectlombok:lombok")
		"testAnnotationProcessor"("org.projectlombok:lombok")

		"testImplementation"("org.springframework.boot:spring-boot-starter-test")
		"testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
	}

	tasks.withType<Test> {
		useJUnitPlatform()
		// colima / 일부 Docker Desktop 환경에서 docker.sock mount 가 안 되어 Ryuk(testcontainers cleanup helper)
		// 부팅이 실패한다. Ryuk 가 없어도 테스트 자체는 동작 — JVM 종료 시 컨테이너 자동 정리만 안 됨.
		environment("TESTCONTAINERS_RYUK_DISABLED", "true")
	}

	tasks.withType<JavaCompile> {
		options.encoding = "UTF-8"
		options.compilerArgs.add("-parameters")
	}
}
