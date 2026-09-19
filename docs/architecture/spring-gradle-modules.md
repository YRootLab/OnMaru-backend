# Spring Gradle module governance

## Current Shape

OnMaru Spring backend is a Gradle multi-module modular monolith.

```text
apps:spring-api
adapters:persistence-jdbc
adapters:tourism-api
modules:audio
modules:catalog
modules:community
modules:identity
modules:insights
modules:journey
modules:operations
modules:shared-web
```

`apps:spring-api` is the Spring Boot composition root. It owns HTTP
controllers, Spring configuration, runtime wiring, application resources, and
application-level integration tests. Business rules live in `modules:*`.
External I/O and technical persistence implementations live in `adapters:*`.

## Dependency Direction

Allowed build dependency directions:

| Direction | Rule |
| --- | --- |
| `apps:spring-api -> modules:*` | The app composes business modules. |
| `apps:spring-api -> adapters:*` | The app wires technical adapters into runtime configuration and integration tests. |
| `adapters:* -> modules:*` | Adapters implement module-owned ports or map provider payloads into module models. |
| `modules:audio -> modules:catalog` | Audio can use catalog identity/projection boundaries for place linking. |

Forbidden directions:

| Direction | Reason |
| --- | --- |
| `modules:* -> apps:spring-api` | Business modules must not know the Spring Boot composition root. |
| `modules:* -> adapters:*` | Module ports own the abstraction; adapters implement it. |
| `adapters:* -> apps:spring-api` | Adapters must stay reusable outside a specific app runtime. |
| Unlisted cross-context module dependency | Add a consumer-owned port or app bridge instead of direct coupling. |

When a new dependency direction is needed, update this document and the
relevant ArchUnit rule in the same PR.

## Build Conventions

`build-logic` owns only repeated build mechanics:

- `java-library` for library subprojects
- Java 21 toolchain
- JUnit Platform task setup

Module-local `build.gradle.kts` files must keep their own dependency
declarations. Do not hide `api`, `implementation`, `testImplementation`,
`runtimeOnly`, `platform`, or `project(...)` dependencies inside convention
plugins.
