plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	runtimeOnly("com.mysql:mysql-connector-j")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-mysql")

	// ADR-001: A→B sync HTTP 호출 보호 (Circuit Breaker).
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")

	implementation("io.micrometer:micrometer-registry-prometheus")

	// Server A 통합 테스트 — 실제 MySQL 위에서 per-request commit + Circuit Breaker 동작 검증.
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")
	testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.named<Jar>("jar") {
	enabled = false
}
