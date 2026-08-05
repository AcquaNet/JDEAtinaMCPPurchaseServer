# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JDE MCP Server — a Spring Boot application that implements a Model Context Protocol (MCP) server, bridging Claude AI and JD Edwards EnterpriseOne. It currently covers three modules: purchase order approval workflows, sales order queries (customer lookup/detail/credit, item search/price), and a sales cart that assembles a multi-line order conversationally before creating it in JDE. Claude.ai / Claude Desktop connect to the server directly over Streamable HTTP.

**Request flow**: Claude AI → MCP Server (this app, port 8080) → Atina Gateway (BSSV operations, `POST /v1/operations/execute`) → JD Edwards EnterpriseOne

## Build & Run Commands

> **JDK**: the pom requires Java 25, but the shell's default `java` (sdkman `current`) is Java 8, which fails with cryptic errors on records/text blocks. Prefix Maven commands with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem`.

```bash
# Build
./mvnw clean package

# Run (dev profile, active by default — see "Environment profiles" below)
./mvnw spring-boot:run

# Run with a specific profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=stage

# Run packaged JAR
java -jar target/JDEMCPServer-0.0.1-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=prod java -jar target/JDEMCPServer-0.0.1-SNAPSHOT.jar

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ApplicationTests
```

To test tools interactively, run the server and launch the MCP Inspector (`npx @modelcontextprotocol/inspector`), connecting with transport "Streamable HTTP" to `http://localhost:8080/mcp`. For Claude.ai access to a local server, expose it with `ngrok http 8080`.

### Environment profiles

Standard Spring profiles: `dev` (default), `stage`, `prod`. `application.properties` holds settings shared by every environment (business/tool defaults, TTLs, timeouts) plus `spring.profiles.active=dev`, so no extra flags are needed for local development. Per-environment overrides live in `application-{profile}.properties`:

- **`application-dev.properties`** — points at services on `localhost` (Atina Gateway, Keycloak, OpenBao with a `localhost` fallback); this is the file that carries today's local defaults.
- **`application-stage.properties`** / **`application-prod.properties`** — every host and credential is sourced from an environment variable with **no default** (`${JDE_ATINA_GATEWAY_BASE_URL}`, `${JDE_KEYCLOAK_ISSUER_URI}`, `${BAO_ADDR}`, `${BAO_TOKEN}`), so a missing var fails startup immediately (`PlaceholderResolutionException`) instead of silently defaulting to `localhost`. Each environment also gets its own H2 file path (`./data/identity-mapping-{stage,prod}`) so they never share a database.

Select a non-default profile with `SPRING_PROFILES_ACTIVE=<profile>` (env var, takes priority over the `application.properties` default) or `-Dspring-boot.run.profiles=<profile>` / `--spring.profiles.active=<profile>`.

## Architecture

**Tech stack**: Java 25, Spring Boot 3.5.7, Spring AI 1.1.0-M3 (MCP server support via `spring-ai-starter-mcp-server-webmvc`), Maven

**Package structure** under `com.atina.jdeMCPServer`:

- **`security/`** — OAuth2 Resource Server. `RealmRoleGuard` enforces per-tool authorization via Keycloak realm roles (`realm_access.roles` claim; approve/reject requires `jde.mcp.security.approver-role`, checked inside the tool since all tools share the `/mcp` endpoint; Atina tokens are always authorized — JDE enforces its own role). `SecurityConfig` accepts two token types on `/mcp`, routed by the `iss` claim: (a) Keycloak JWTs (realm `jde-integration`, audience `atina-mcp-server`; OIDC discovery deferred via `SupplierJwtDecoder` so startup/tests work without Keycloak); (b) HS256 JWTs from the Atina microservice (issuer `jde.atina.jwt.issuer`, shared secret `ATINA_JWT_SECRET` — these ARE the JDE session token, used directly as `X-Approver-Token`). `AuthenticatedJdeIdentity` exposes the Keycloak subject/claims of the current request.
- **`auth/`** — Session token resolution for JDE, sourced from Atina (no direct JDE login exists anymore — see below).
  - `JdeAuthService.getOrCreateToken()` is the single choke point for JDE tokens. Resolution order: (0) Atina bearer token used directly, if the caller authenticated as the Atina microservice; (0.b) Atina session token resolved per `jde.atina.session-source` (JWT claim `atina_token` and/or OpenBao, see below); (1) manual `jde_login` token for this MCP session (`Mcp-Session-Id` header, falling back to remote IP — what the MCP Inspector uses). If none resolve, throws a clear error asking for `jde_login`. The `Authorization` header carries the Keycloak JWT and is never used as a JDE token.
  - `JdeTokenStore` keeps JWT tokens in a ConcurrentHashMap keyed by session ID, with expiry parsed from the JWT `exp` claim and a 5-minute expiry buffer.
- **`identity/`** — `IdentityResolver` interface resolving Keycloak `sub` → `JdeIdentity` (jdeUser/environment/role), impls `NativeMappingResolver` (reads `identity_mapping` table, H2 file DB + Flyway migrations in `db/migration/`) / `FederatedAttributeResolver` (LDAP stub, not implemented). **Currently unused** — this backed the old Identity Bridge automatic-login fallback (Keycloak `sub` → native JDE credentials → login), which was removed together with the Mulesoft REST backend it depended on (`JdeAuthClient`, deleted). Left in place in case a non-Mulesoft login mechanism is built on top of it later.
- **`vault/`** — `CredentialVault` interface + `OpenBaoCredentialVault`: reads the real JDE password from OpenBao KV v2 at `secret/data/jde/{jdeUser}` (`BAO_ADDR`/`BAO_TOKEN` env vars; never stored locally). Distinguishes `VaultUnavailableException` (infra) from `VaultCredentialNotFoundException` (missing secret). **Currently unused** for the same reason as `identity/` above (only consumer was the deleted Identity Bridge); `AtinaSessionVault` (also in `vault/`) is unrelated and still active — it stores Atina session tokens, not JDE passwords.
- **`purchase/`** — Purchase order approval module: `services/JdePurchaseOrderClient` (BSSV operations via the Atina Gateway), `tools/` (MCP tools `jde_login`, `jde_list_pending_purchase_orders`, `jde_get_purchase_order_detail`, `jde_approve_purchase_order`, `jde_reject_purchase_order`), and `prompts/JDEPurPrompts` (`@McpPrompt` definitions guiding the workflow).
- **`salesorder/`** — Sales order module: `services/JdeSalesOrderClient` (BSSV operations via the Atina Gateway) and `tools/` (MCP tools `jde_lookup_customer_by_name`, `jde_get_customer_detail`, `jde_get_customer_credit_info`, `jde_search_items`, `jde_get_item_price`, `jde_get_item_list_price`). Tools return structured JSON records (`salesorder/model/`, `@McpTool(generateOutputSchema = true)`), not plain strings — see "MCP tool conventions" below. `JdeSalesOrderClient.createSalesOrder(...)` (operation `processSalesOrderV5`, `salesorder/model/CreateSalesOrderRequest`/`CreateSalesOrderResponse`) is the only write operation in this module — it's not called by any `salesorder/tools/` tool directly, only by `cart/` (below).
- **`cart/`** — Sales cart module: an in-memory shopping-cart draft (`cart/model/SalesCart`, an immutable record with `with*` mutators applied atomically via `SalesCartRepository.update()`, same `ConcurrentHashMap.compute()` idiom as the rest of the map) that lets a conversation assemble a multi-line sales order before creating it in JDE with a single `JdeSalesOrderClient.createSalesOrder(...)` call. One active cart per authenticated owner: `InMemorySalesCartRepository` keys primarily by `Mcp-Session-Id` (TTL + `@Scheduled` cleanup, same pattern as `purchase/services/PendingPurchaseOrderStore`), with a fallback lookup + re-home by `ownerId` (`SalesCartService.findActiveCart`) for when the MCP client reconnects mid-conversation and issues a new session id — confirmed happening in practice with Claude.ai's remote connector. Tools (`cart/tools/JdeSalesCartTools`, none take `sessionId`/`userId` as a parameter — always resolved server-side via `CartOwnerResolver`): `jde_create_current_sales_cart`, `jde_add_item_to_current_sales_cart`, `jde_update_current_sales_cart_item`, `jde_remove_current_sales_cart_item`, `jde_get_current_sales_cart`, `jde_validate_current_sales_cart`, `jde_clear_current_sales_cart`, `jde_submit_current_sales_cart` (the only one that writes to JDE — gated behind explicit `confirm=true` and an optimistic-concurrency `expectedCartVersion` check). Result records add an `errorCode` field (`cart/model/CartErrorCodes`) alongside the shared `salesorder.model.ToolStatus`, for the cart's more granular business error codes (`CART_NOT_FOUND`, `CART_VERSION_CONFLICT`, `CUSTOMER_MISMATCH`, etc).
  - `jde_validate_current_sales_cart` re-queries JDE for every line's current price (always) and stock availability (if `jde.cart.check-availability-on-validate=true`) right before the user gives final confirmation, and diffs the result against what the cart already had. No changes → cart moves to `READY_FOR_CONFIRMATION`. Something changed (price moved, stock dropped below the requested quantity) → every change is reported in `changes[]`, `requiresReconfirmation=true`, and the cart stays/returns to `OPEN`, forcing the assistant to show the new values and get the user to confirm again before calling `jde_submit_current_sales_cart`. It does **not** increment `cart.version` — it's a resync against JDE, not a new decision by the user, so the `expectedCartVersion` optimistic-concurrency check in `jde_submit_current_sales_cart` still matches afterward.

**Token propagation**: Every backend call sends the JWT in the `X-Approver-Token` (purchase) or `Authorization: Bearer` (sales order Gateway calls) header; if the backend returns a renewed token in the response, `JdeAuthService.updateTokenFromResponse()` stores it for the session.

**Backend clients**: Both service clients use Spring WebClient (reactive) but call `.block()` — the MCP protocol is synchronous (`spring.ai.mcp.server.type=sync`). Response timeouts are set to 10 minutes.

**MCP tool conventions**: Tools are `@Component` classes with `@McpTool`/`@McpToolParam` annotations (from `org.springaicommunity.mcp.annotation`). Tool descriptions are long prompts that embed presentation/business instructions for Claude ("never invent identifiers", ask for disambiguation on multiple matches, follow-up questions). Tools never throw — exceptions are caught, logged, and turned into a `FAILED`/error result instead. Two conventions coexist today:
  - **Purchase order tools** (older): return plain human-readable strings, including Markdown-table formatting instructions in the description.
  - **Sales order tools** (current pattern for new/migrated tools): `@McpTool(generateOutputSchema = true)`, return a `record` from `salesorder/model/` instead of `String` — the MCP Java SDK (`mcp-core:0.14.0`) publishes the record's JSON Schema as `Tool.outputSchema` in `tools/list` and serializes the return value into `CallToolResult.structuredContent`. Every such record has a `ToolStatus status` field (`OK`/`INVALID_REQUEST`/`IN_PROGRESS`/`FAILED`/`CANCELLED`) plus a `message` — **fields must never be `null`** (use `""`, `0`, `List.of()`, or a static `empty()` factory instead): the SDK validates `structuredContent` against `outputSchema` at call time and rejects `null` on any field whose declared type doesn't include it, turning the whole call into an `isError: true` response. The SDK also auto-mirrors `structuredContent` into a `content[0].text` JSON blob for backward compatibility with clients that don't read `structuredContent` — this is unconditional SDK behavior, not something tool code controls.

**PO identifiers**: A purchase order is identified by the four-field composite key `documentOrderTypeCode` + `documentOrderInvoiceNumber` + `documentCompanyKeyOrderNo` + `documentSuffix`. Approve/reject both go through `/v1/processPurchaseOrderApproveReject` with `action` `"A"` or `"R"`; remarks are truncated to 30 characters (JDE backend limit).

## Key Configuration

Split across `application.properties` (common to every profile) and `application-{dev,stage,prod}.properties` (per-environment overrides — see "Environment profiles" above).

Profile-specific (in `application-{profile}.properties`):
- `jde.atina.gateway.base-url` — Atina Gateway URL for BSSV operations, the only backend URL left (dev default: `http://localhost:8086`; stage/prod: `${JDE_ATINA_GATEWAY_BASE_URL}`, required)
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` — Keycloak issuer (dev default: `http://localhost:8180/realms/jde-integration`; stage/prod: `${JDE_KEYCLOAK_ISSUER_URI}`, required)
- `jde.vault.addr` / `jde.vault.token` — OpenBao (dev: `BAO_ADDR`/`BAO_TOKEN` env vars with `localhost` fallback; stage/prod: same env vars, no fallback — required)
- `spring.datasource.url` — H2 file DB, one file per profile (`./data/identity-mapping[-stage|-prod]`, gitignored) for `identity_mapping`; Flyway runs migrations on startup. Seed dev users with `scripts/seed-identity-dev.sh` (table currently unread by any code path — see `identity/` above)

Common (in `application.properties`):
- `jde.mcp.security.expected-audience` — Required `aud` claim in incoming tokens (default: `atina-mcp-server`, same client id assumed in every environment)
- `jde.identity.resolver` — Active `IdentityResolver` impl (`native` | `federated`) — currently unused (see `identity/` above)
- `jde.atina.session-source` — Atina session token strategy (`claim` | `vault` | `claim-then-vault` | `vault-then-claim`); same default in all profiles today, override per-profile if a rollout needs to diverge
- `jde.purchase.*` / `jde.pricing.*` — JDE business defaults for purchase-order and pricing tools (order type, business unit, processing versions, pending-order cache TTL), not environment-dependent
- `jde.cart.*` — sales cart repository TTL/cleanup interval/max active carts, and availability/credit-check toggles (`check-availability-on-add`, `check-availability-on-validate`, `check-credit-on-submit`, `block-on-credit-exceeded`)
- `jde.sales-order.*` — `processSalesOrderV5` payload defaults never left to the LLM: `document-type-code`, `company` vs `document-company` (distinct fields, different padding — see `JdeSalesOrderClient.createSalesOrder`), `line-type-code`, `action-type`, `processing-version`, `default-requested-date-lead-days` (requested date must be in the future relative to order date or JDE warns about the pick date), plus `submit.async.*` (same `LongRunningTaskRegistry` kill-switch pattern as the async tools below)
- MCP endpoint is `/mcp` (`spring.ai.mcp.server.streamable-http.mcp-endpoint`)
- Tomcat timeouts set to 600s for long-lived SSE connections

## Testing

Minimal test coverage — only a Spring context load test exists (`ApplicationTests.java`). When adding tests, use `spring-boot-starter-test` (JUnit 5, Mockito, Spring MockMvc).
