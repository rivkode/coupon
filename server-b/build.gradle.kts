plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	implementation("io.micrometer:micrometer-registry-prometheus")
}

tasks.named<Jar>("jar") {
	enabled = false
}
