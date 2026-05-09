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

	// ADR-002: Outbox poller 가 coupon-issue-result publish, Kafka consumer 가 coupon-issue-request consume.
	// at-least-once + (user_id, coupon_type_id) UNIQUE 로 의미적 exactly-once.
	implementation("org.springframework.kafka:spring-kafka")
	testImplementation("org.springframework.kafka:spring-kafka-test")

	// 비관적 락 정합성 검증용 — 실제 MySQL InnoDB 와 통합 테스트.
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")

	implementation("io.micrometer:micrometer-registry-prometheus")
}

tasks.named<Jar>("jar") {
	enabled = false
}

