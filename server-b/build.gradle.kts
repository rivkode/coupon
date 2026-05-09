plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	// Lettuce 풀링 활성화에 필요.
	implementation("org.apache.commons:commons-pool2")

	// ADR-008/009: Kafka producer (coupon-issue-request) + consumer (coupon-issue-result).
	implementation("org.springframework.kafka:spring-kafka")
	testImplementation("org.springframework.kafka:spring-kafka-test")

	implementation("io.micrometer:micrometer-registry-prometheus")

	// Server B 통합 테스트 — 실제 Redis 위에서 적재 atomicity + Kafka round-trip + 스케줄러 보완 검증.
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.wiremock:wiremock-standalone:3.10.0")
	testImplementation("org.awaitility:awaitility")
}

tasks.named<Jar>("jar") {
	enabled = false
}
