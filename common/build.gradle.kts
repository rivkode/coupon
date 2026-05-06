plugins {
	`java-library`
}

dependencies {
	api("org.springframework.boot:spring-boot-starter-validation")
}

tasks.named<Jar>("jar") {
	enabled = true
}
