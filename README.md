# JDE MCP Server

A Model Context Protocol (MCP) server that exposes JD Edwards (JDE) purchase order approval workflows to AI assistants like Claude. It acts as a bridge between Claude and the JDE backend through a Mulesoft API layer.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Authentication](#authentication)
- [Available Tools](#available-tools)
- [Usage Examples](#usage-examples)
- [Error Handling](#error-handling)

---

## Overview

The JDE MCP Server allows Claude (or any MCP-compatible AI assistant) to interact with JD Edwards EnterpriseOne purchase order data in natural language. Users can list pending POs, review their details, and approve or reject them — all from a conversational interface.

---

## Architecture

```
User (Claude.ai)
      │
      ▼
Claude AI (MCP Client)
      │  MCP over HTTP (SSE)
      ▼
JDE MCP Server
https://<your-host>/mcp/message
      │  REST API calls
      ▼
Mulesoft API Layer
      │
      ▼
JD Edwards EnterpriseOne
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
> ngrok http 3000
> ```
> Then use the generated `https://<random>.ngrok-free.app/mcp/message` as the URL.

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



