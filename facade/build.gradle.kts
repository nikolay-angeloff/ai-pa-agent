plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.expense"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.3")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    // Gmail ingestion
    implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")

    // Spring AI — vision extraction + embeddings (Step 4)
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // PDF text extraction for receipts/invoices as PDFs
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Qdrant vector store (Step 4)
    implementation("io.qdrant:client:1.18.1")
    runtimeOnly("io.grpc:grpc-netty-shaded:1.75.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
