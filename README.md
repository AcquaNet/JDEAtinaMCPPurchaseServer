# JDE MCP Server

A Model Context Protocol (MCP) server that exposes JD Edwards (JDE) purchase order approval and sales order workflows to AI assistants like Claude. It acts as a bridge between Claude and the JDE backend through a Mulesoft API layer.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
  - [Adding the MCP Server to Claude.ai](#adding-the-mcp-server-to-claudeai)
  - [Adding the MCP Server to Claude Desktop](#adding-the-mcp-server-to-claude-desktop)
- [Testing with MCP Inspector](#testing-with-mcp-inspector)
- [Authentication](#authentication)
- [Available Tools](#available-tools)
  - [Purchase Order Tools](#purchase-order-tools)
  - [Sales Order Tools](#sales-order-tools)
- [Usage Examples](#usage-examples)
- [Error Handling](#error-handling)

---

## Overview

The JDE MCP Server allows Claude (or any MCP-compatible AI assistant) to interact with JD Edwards EnterpriseOne data in natural language. Currently supported modules:

- **Purchase Orders** — List pending POs, review details, approve or reject them.
- **Sales Orders** — Query customer credit and financial exposure information.

---

## Architecture

```
User (Claude.ai / Claude Desktop)
      │
      ▼
Claude AI (MCP Client)
      │  MCP over Streamable HTTP
      ▼
JDE MCP Server (this application, port 8080)
      │  REST API calls
      ├──────────────────────────────┐
      ▼                              ▼
Mulesoft Purchase API           Mulesoft Sales Order API
(port 8081)                     (port 8083)
      │                              │
      ▼                              ▼
JD Edwards EnterpriseOne        JD Edwards EnterpriseOne
```

---

## Prerequisites

| Requirement | Details |
|---|---|
| MCP-compatible client | Claude.ai (Pro / Team / Enterprise) or any MCP client |
| JDE credentials | A valid JDE username and password |
| Network access | Client must be able to reach the MCP server URL |
| Server runtime | The MCP server must be running and publicly reachable (e.g., via ngrok for local dev) |

---

## Configuration

### Adding the MCP Server to Claude.ai

1. Open **Claude.ai** and go to **Settings → Integrations** (or the Connectors section).
2. Click **Add MCP Server**.
3. Enter the server details:

| Field | Value |
|---|---|
| Name | `JDE Atina` (or any descriptive label) |
| URL | `https://<your-host>/mcp/message` |
| Transport | SSE (Server-Sent Events) |

4. Save and confirm the connection is active.

> **Local development with ngrok:**  
> If running the server locally, expose it with:
> ```bash
> ngrok http 8080
> ```
> Then use the generated `https://<random>.ngrok-free.app/mcp/message` as the URL.

### Adding the MCP Server to Claude Desktop

1. Open **Claude Desktop** and go to **Settings → Developer → Edit Config**.
2. This opens the file `claude_desktop_config.json`. Add the MCP server under `mcpServers`:

```json
{
  "mcpServers": {
    "jde-atina": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

> If the server is remote or exposed via ngrok, replace the URL accordingly:
> ```json
> {
>   "mcpServers": {
>     "jde-atina": {
>       "url": "https://<your-host>/mcp"
>     }
>   }
> }
> ```

3. Save the file and restart Claude Desktop.
4. You should see the MCP tools (hammer icon) available in the chat input.

#### Pre-authenticated token (skip login)

To avoid calling `jde_login` on every conversation, you can pass a JDE token directly in the Claude Desktop config using the `headers` field. The server will pick it up automatically:

```json
{
  "mcpServers": {
    "jde-atina": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer <your-jde-token>"
      }
    }
  }
}
```

With this configuration, all MCP tools work immediately without calling `jde_login` first. The server reads the `Authorization` header, stores the token for the session, and reuses it for all subsequent calls.

> ⚠️ If the token eventually expires, you will need to update it manually in the config and restart Claude Desktop.

---

## Testing with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is a developer tool for testing and debugging MCP servers interactively.

### 1. Start the MCP server

```bash
./mvnw spring-boot:run
```

### 2. Launch the Inspector

In a separate terminal:

```bash
npx @modelcontextprotocol/inspector
```

This opens a web UI at `http://localhost:6274`.

### 3. Connect to the server

In the Inspector UI:

| Field | Value |
|---|---|
| Transport Type | **Streamable HTTP** |
| URL | `http://localhost:8080/mcp` |

Click **Connect**.

### 4. Test the tools

1. Go to the **Tools** tab — you should see all registered tools (`jde_login`, `jde_list_pending_purchase_orders`, `jde_get_customer_credit_info`, etc.).
2. Call `jde_login` with valid JDE credentials.
3. Call any other tool (e.g., `jde_get_customer_credit_info` with `customerNumber: 4242`).

> **Tip:** The Inspector does not send the `Mcp-Session-Id` header. The server falls back to using your IP address as session identifier, so all calls from the Inspector share the same session.

---

## Authentication

**Every session must begin with a login call.** The server maintains a session token after authentication; all subsequent tool calls reuse it until the session expires.

### Login Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `user` | string | ✅ | JDE username (e.g., `JDOE`) |
| `password` | string | ✅ | JDE password |

### Session Behavior

- Session expiry is returned in the login response (e.g., `2026-03-28T13:29:25Z`).
- If any tool returns a session error, call `jde_login` again and retry the operation.
- Credentials are **never stored or echoed back** to the user.

---

## Available Tools

### Purchase Order Tools

### 1. `jde_login`

Authenticates the user against JDE via the Mulesoft API. Must be called before any other tool.

```json
{
  "tool": "jde_login",
  "parameters": {
    "user": "JDOE",
    "password": "yourpassword"
  }
}
```

**Returns:** Confirmation of authenticated user and session expiry timestamp.

---

### 2. `jde_list_pending_purchase_orders`

Lists all purchase orders currently pending approval for the logged-in user.

```json
{
  "tool": "jde_list_pending_purchase_orders",
  "parameters": {
    "limit": 10
  }
}
```

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `limit` | integer | ❌ | 10 | Max number of POs to return |

**Returns:** Array of pending purchase orders with fields including:

| Field | Description |
|---|---|
| `documentOrderTypeCode` | PO type (e.g., `OP`) |
| `documentOrderInvoiceNumber` | PO number (e.g., `5082`) |
| `documentCompanyKeyOrderNo` | Company key (e.g., `00001`) |
| `documentSuffix` | Suffix (e.g., `000`) |
| `entityNameSupplier` | Supplier name |
| `entityNameShipTo` | Ship-to entity name |
| `amountGross` | Total gross amount |
| `currencyCodeBase` | Currency (e.g., `USD`) |
| `dateRequested` | Request date |
| `dateTransaction` | Transaction date |
| `calculateValues.daysOld` | How many days the PO has been waiting |

---

### 3. `jde_get_purchase_order_detail`

Retrieves full header and line-item detail for a specific purchase order.

```json
{
  "tool": "jde_get_purchase_order_detail",
  "parameters": {
    "documentOrderTypeCode": "OP",
    "documentOrderInvoiceNumber": 5082,
    "documentCompanyKeyOrderNo": "00001",
    "documentSuffix": "000"
  }
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `documentOrderTypeCode` | string | ✅ | PO type code (e.g., `OP`) |
| `documentOrderInvoiceNumber` | integer | ✅ | PO invoice number |
| `documentCompanyKeyOrderNo` | string | ✅ | Company key |
| `documentSuffix` | string | ✅ | Document suffix |

**Returns:** Full PO object including:
- **Header:** supplier, ship-to, dates, amounts, currency, business unit, remark
- **Line items:** item ID, description, quantity, unit price, extended amount, UOM

> ⚠️ Always use values returned directly by `jde_list_pending_purchase_orders`. Never guess or invent PO identifiers.

---

### 4. `jde_approve_purchase_order`

Approves a specific pending purchase order.

```json
{
  "tool": "jde_approve_purchase_order",
  "parameters": {
    "documentOrderTypeCode": "OP",
    "documentOrderInvoiceNumber": 5082,
    "documentCompanyKeyOrderNo": "00001",
    "documentSuffix": "000",
    "remark": "Approved after review"
  }
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `documentOrderTypeCode` | string | ✅ | PO type code |
| `documentOrderInvoiceNumber` | integer | ✅ | PO invoice number |
| `documentCompanyKeyOrderNo` | string | ✅ | Company key |
| `documentSuffix` | string | ✅ | Document suffix |
| `remark` | string | ✅ | Approval remark (truncated to 30 chars) |

---

### 5. `jde_reject_purchase_order`

Rejects a specific pending purchase order.

```json
{
  "tool": "jde_reject_purchase_order",
  "parameters": {
    "documentOrderTypeCode": "OP",
    "documentOrderInvoiceNumber": 5082,
    "documentCompanyKeyOrderNo": "00001",
    "documentSuffix": "000",
    "remark": "Missing supporting documentation"
  }
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `documentOrderTypeCode` | string | ✅ | PO type code |
| `documentOrderInvoiceNumber` | integer | ✅ | PO invoice number |
| `documentCompanyKeyOrderNo` | string | ✅ | Company key |
| `documentSuffix` | string | ✅ | Document suffix |
| `remark` | string | ✅ | Rejection reason (truncated to 30 chars) |

---

### Sales Order Tools

### 6. `jde_get_customer_credit_info`

Retrieves the credit limit and financial exposure for a JDE customer.

```json
{
  "tool": "jde_get_customer_credit_info",
  "parameters": {
    "customerNumber": 4242
  }
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `customerNumber` | integer | ✅ | JDE customer number (address book number) |

**Returns:** Customer credit and financial information:

| Field | Description |
|---|---|
| `customerNumber` | Customer address book number |
| `company` | JDE company code (e.g., `00000`) |
| `currencyCode` | Currency (e.g., `USD`) |
| `creditLimitAmount` | Approved credit limit |
| `totalExposureAmount` | Current total exposure (open orders + AR balance) |

**Example response:**

```json
{
  "customerNumber": 4242,
  "company": "00000",
  "currencyCode": "USD",
  "creditLimitAmount": 50000,
  "totalExposureAmount": 2454092.68
}
```

> The assistant will calculate **Available Credit** (Credit Limit − Total Exposure) and highlight when the customer exceeds their credit limit.

---

## Usage Examples

### Typical Approval Workflow

```
User:  "Show me my pending purchase orders"
       → jde_login (if no active session)
       → jde_list_pending_purchase_orders(limit=10)

User:  "Show me the detail for OP-5082"
       → jde_get_purchase_order_detail(OP, 5082, 00001, 000)

User:  "Approve it"
       → jde_approve_purchase_order(OP, 5082, 00001, 000, remark="Approved after review")
```

### Example: Natural Language Session with Claude

```
User:    Give me all pending purchases for approval.
Claude:  [calls jde_login, then jde_list_pending_purchase_orders]
         → Returns table with OP-5082 and OP-5083, both 120 days old, $1,315.13 USD each.

User:    Show me detail of OP-5082.
Claude:  [calls jde_get_purchase_order_detail]
         → Returns header + 2 line items (Carburetor x9 and x6 at $87.68 each).

User:    Approve it.
Claude:  [asks for remark, then calls jde_approve_purchase_order]
         → PO approved successfully.
```

### Example: Customer Credit Check

```
User:    What's the credit status for customer 4242?
Claude:  [calls jde_login if needed, then jde_get_customer_credit_info]
         → Returns credit limit $50,000 USD, total exposure $2,454,092.68.
         → Highlights: customer is significantly over their credit limit.
```

---

## Error Handling

| Error | Cause | Resolution |
|---|---|---|
| `Session not found` | No active JDE session | Call `jde_login` first |
| `Session expired` | Token timed out | Call `jde_login` again and retry |
| `Invalid credentials` | Wrong username or password | Verify and retry |
| `PO not found` | Wrong identifiers used | Use values directly from list tool |
| Network error | MCP server unreachable | Check server is running and URL is correct |

---

## Security Notes

- Credentials are passed at runtime only and are **never persisted** by the MCP server or the AI client.
- Use HTTPS for the MCP server URL at all times.
- For production, replace ngrok with a stable, authenticated endpoint.
- Remark fields are capped at **30 characters** by the JDE backend.

---

## License

Internal use only. Contact your Mulesoft / JDE integration team for access and deployment details.

## Claude Desktop Screenshot

See [IMAGES.md](IMAGES.md)



