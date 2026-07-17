# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JDE MCP Server — a Spring Boot application that implements a Model Context Protocol (MCP) server, bridging Claude AI and JD Edwards EnterpriseOne. It currently covers two modules: purchase order approval workflows and sales order customer credit queries. Claude.ai / Claude Desktop connect to the server directly over Streamable HTTP.

**Request flow**: Claude AI → MCP Server (this app, port 8080) → Mulesoft API (port 8083) → JD Edwards EnterpriseOne

## Build & Run Commands

> **JDK**: the pom requires Java 25, but the shell's default `java` (sdkman `current`) is Java 8, which fails with cryptic errors on records/text blocks. Prefix Maven commands with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem`.

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

To test tools interactively, run the server and launch the MCP Inspector (`npx @modelcontextprotocol/inspector`), connecting with transport "Streamable HTTP" to `http://localhost:8080/mcp`. For Claude.ai access to a local server, expose it with `ngrok http 8080`.

## Architecture

**Tech stack**: Java 25, Spring Boot 3.5.7, Spring AI 1.1.0-M3 (MCP server support via `spring-ai-starter-mcp-server-webmvc`), Maven

**Package structure** under `com.atina.jdeMCPServer`:

- **`security/`** — OAuth2 Resource Server against Keycloak (realm `jde-integration`). `SecurityConfig` requires a valid Keycloak JWT on `/mcp` (audience must match `jde.mcp.security.expected-audience`, i.e. client `claude-desktop-mcp`); the `JwtDecoder` is wrapped in `SupplierJwtDecoder` so OIDC discovery is deferred to the first request — startup and tests work without Keycloak running. `AuthenticatedJdeIdentity` exposes the Keycloak subject/claims of the current request (the Keycloak sub → JDE credential identity bridge is not built yet).
- **`auth/`** — Session-based authentication against JDE via Mulesoft.
  - `JdeAuthService` resolves the session from the `Mcp-Session-Id` header (falls back to remote IP, which is what the MCP Inspector uses since it doesn't send that header). The `Authorization` header carries the Keycloak JWT and is never used as a JDE token; the JDE token is only obtained via `jde_login`.
  - `JdeAuthClient` posts credentials to Mulesoft `/v1/login` (JDE environment `JDV920`, role `*ALL` are hardcoded there) and reads the JWT from the `X-Approver-Token` response header.
  - `JdeTokenStore` keeps JWT tokens in a ConcurrentHashMap keyed by session ID, with expiry parsed from the JWT `exp` claim and a 5-minute expiry buffer.
- **`purchase/`** — Purchase order approval module: `services/JdePurchaseOrderClient` (REST calls to Mulesoft), `tools/` (MCP tools `jde_login`, `jde_list_pending_purchase_orders`, `jde_get_purchase_order_detail`, `jde_approve_purchase_order`, `jde_reject_purchase_order`), and `prompts/JDEPurPrompts` (`@McpPrompt` definitions guiding the workflow).
- **`salesorder/`** — Sales order module: `services/JdeSalesOrderClient` and `tools/JdeCustomerCreditTool` (MCP tool `jde_get_customer_credit_info`).

**Token propagation**: Every backend call sends the JWT in the `X-Approver-Token` request header; if Mulesoft returns a renewed token in the same response header, `JdeAuthService.updateTokenFromResponse()` stores it for the session.

**Backend clients**: Both service clients use Spring WebClient (reactive) but call `.block()` — the MCP protocol is synchronous (`spring.ai.mcp.server.type=sync`). Response timeouts are set to 10 minutes.

**MCP tool conventions**: Tools are `@Component` classes with `@McpTool`/`@McpToolParam` annotations (from `org.springaicommunity.mcp.annotation`). Tool descriptions are long prompts that embed presentation instructions for Claude (Markdown table formats, sorting, "never invent identifiers", follow-up questions). Tools return human/model-readable strings — including on errors (exceptions are caught, logged, and turned into guidance text) — rather than throwing.

**PO identifiers**: A purchase order is identified by the four-field composite key `documentOrderTypeCode` + `documentOrderInvoiceNumber` + `documentCompanyKeyOrderNo` + `documentSuffix`. Approve/reject both go through `/v1/processPurchaseOrderApproveReject` with `action` `"A"` or `"R"`; remarks are truncated to 30 characters (JDE backend limit).

## Key Configuration

`src/main/resources/application.properties`:
- `jde.api.base-url` — Mulesoft backend URL for auth + purchase orders (default: `http://localhost:8083/api`)
- `jde.so.api.base-url` — Mulesoft backend URL for sales orders (same default)
- `jde.api.login-timeout-minutes` — Login request timeout / token expiry buffer (default: 5)
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` — Keycloak issuer (default: `http://localhost:8180/realms/jde-integration`)
- `jde.mcp.security.expected-audience` — Required `aud` claim in incoming tokens (default: `claude-desktop-mcp`)
- MCP endpoint is `/mcp` (`spring.ai.mcp.server.streamable-http.mcp-endpoint`)
- Tomcat timeouts set to 600s for long-lived SSE connections

## Testing

Minimal test coverage — only a Spring context load test exists (`ApplicationTests.java`). When adding tests, use `spring-boot-starter-test` (JUnit 5, Mockito, Spring MockMvc).
