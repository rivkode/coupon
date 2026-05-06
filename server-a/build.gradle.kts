plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	runtimeOnly("com.mysql:mysql-connector-j")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-mysql")

	implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")
	implementation("com.bucket4j:bucket4j_jdk17-redis-common:8.14.0")
	implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")

	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
	implementation("io.github.resilience4j:resilience4j-timelimiter:2.3.0")
	implementation("io.github.resilience4j:resilience4j-reactor:2.3.0")

	implementation("io.micrometer:micrometer-registry-prometheus")
}

tasks.named<Jar>("jar") {
	enabled = false
}
