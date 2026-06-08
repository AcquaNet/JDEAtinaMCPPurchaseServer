# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JDE MCP Purchase Server — a Spring Boot application that implements a Model Context Protocol (MCP) server, bridging Claude AI and JD Edwards EnterpriseOne for purchase order approval workflows. The server exposes MCP tools over Streamable HTTP (SSE) that Claude.ai connects to directly.

**Request flow**: Claude AI → MCP Server (this app, port 8080) → Mulesoft API (port 8081) → JD Edwards EnterpriseOne

## Build & Run Commands

```bash
# Build
./mvnw clean package

# Run
./mvnw spring-boot:run

# Run packaged JAR
java -jar target/JDEMCPServer-0.0.1-SNAPSHOT.jar

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ApplicationTests
```

## Architecture

**Tech stack**: Java 25, Spring Boot 3.5.7, Spring AI 1.1.0-M3 (MCP server support), Maven

**Package structure** under `com.atina.jdeMCPServer`:

- **`auth/`** — Session-based authentication against JDE via Mulesoft. `JdeAuthService` resolves sessions from the `Mcp-Session-Id` header. `JdeTokenStore` keeps JWT tokens in a ConcurrentHashMap with expiry tracking (parsed from JWT `exp` claim). Tokens expire with a 5-minute buffer.
- **`purchase/services/`** — `JdePurchaseOrderClient` makes REST calls to the Mulesoft backend using Spring WebClient (reactive).
- **`purchase/tools/`** — MCP tool definitions annotated with `@McpTool`. Five tools: `jde_login`, `jde_list_pending_purchase_orders`, `jde_get_purchase_order_detail`, `jde_approve_purchase_order`, `jde_reject_purchase_order`.
- **`purchase/prompts/`** — `JDEPurPrompts` provides MCP prompts (annotated with `@McpPrompt`) that guide Claude on how to interact with the JDE workflow.

**MCP transport**: Streamable HTTP at `/mcp` endpoint (configured in `application.properties` via `spring.ai.mcp.server.*`). Protocol is synchronous (`spring.ai.mcp.server.type=sync`).

## Key Configuration

`src/main/resources/application.properties`:
- `jde.api.base-url` — Mulesoft backend URL (default: `http://localhost:8081/api`)
- `jde.api.login-timeout-minutes` — Token expiry buffer (default: 5)
- `DANGEROUSLY_OMIT_AUTH=true` — Disables MCP auth (development only)
- Tomcat timeouts set to 600s for long-lived SSE connections

## Testing

Minimal test coverage — only a Spring context load test exists (`ApplicationTests.java`). When adding tests, use `spring-boot-starter-test` (JUnit 5, Mockito, Spring MockMvc).
