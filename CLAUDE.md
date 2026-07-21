# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Compile
mvn clean compile

# Run tests
mvn clean test

# Full build (compile + test + package)
mvn clean package

# Run application
mvn spring-boot:run
```

## Environment Notes

- **JDK**: JDK 17 (Microsoft OpenJDK `ms-17.0.19`). Use `JAVA_HOME=C:/Users/30287/.jdks/ms-17.0.19` for CLI builds.
- **Maven**: 3.9.11
- **Spring Boot**: 3.2.0

## Architecture

Standard Spring Boot 3.x project structure:

```
src/main/java/com/example/mission/
├── MissionApplication.java          # Entry point
├── common/
│   ├── Result.java                  # Unified API response wrapper (code, message, data)
│   ├── BusinessException.java       # Custom runtime exception with business error code
│   ├── GlobalExceptionHandler.java  # @RestControllerAdvice: handles BusinessException, validation errors, and generic exceptions
│   └── StartupRunner.java           # Logs startup completion via CommandLineRunner
├── config/
│   ├── AppConfig.java               # Empty configuration class (placeholder)
│   └── RestTemplateConfig.java      # RestTemplate bean with Authorization header interceptor
└── controller/
    └── MyController.java            # GET /call-api?city=xxx — proxies weather API from seniverse.com
```

### Key Patterns

- **API Response**: All responses go through `Result<T>` wrapper. The `GlobalExceptionHandler` provides three tiers: business exceptions (400), validation errors (400), and unknown errors (500).
- **Bean collision note**: `RestTemplateConfig` defines the `restTemplate` bean (with interceptor). `AppConfig` is an empty config class — do not add a duplicate `restTemplate` bean.
- **External API**: `MyController` calls seniverse.com weather API. The API key is hardcoded in the controller (`SLiqyqp-myXKw1e2J`).

## Dependencies

- spring-boot-starter-web (Spring MVC + embedded Tomcat)
- spring-boot-starter-data-mongodb (MongoDB driver, requires a running MongoDB instance for full context)
- mysql-connector-j (MySQL driver, runtime scope)
- lombok (annotation processing)
