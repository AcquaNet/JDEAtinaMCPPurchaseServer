# Tools MCP lentos: motor de tareas en background

Guía de referencia para conectar un tool MCP lento (que puede tardar minutos
contra JDE) al motor genérico `LongRunningTaskRegistry`, evitando que el
cliente MCP (Slack, Claude Desktop, etc.) lo corte por timeout. Ya conectado
a `jde_list_pending_purchase_orders` y `jde_search_items` — usar esos dos como
ejemplo concreto la próxima vez que haga falta aplicar el patrón a otro tool.

## Por qué existe esto

Muchos clientes MCP tienen su propio timeout de infraestructura (Slack,
posiblemente otros) que no se puede extender desde el servidor con ningún
mecanismo del protocolo — ni con `notifications/progress` (Claude Desktop no
lo respeta, y aunque lo respetara, mantiene la llamada original abierta, que
es justo lo que hay que evitar), ni con el `request-timeout` de Spring AI
(verificado por bytecode: solo aplica a requests que el *servidor* le manda
al *cliente* — sampling/elicitation — no a `tools/call` entrante).

El mecanismo oficial del protocolo para esto es **SEP-2663 ("Tasks
Extension")**: `tools/call` devuelve un `CreateTaskResult` en vez de
bloquear, y el cliente hace polling con `tasks/get`/`tasks/update`/`tasks/cancel`
hasta un estado terminal (`working`/`input_required`/`completed`/`failed`/`cancelled`).
**No es adoptable todavía** (revisar esto de nuevo si volvés a esta guía más
adelante, puede haber cambiado):

- SDK Java de MCP: [`modelcontextprotocol/java-sdk#1013`](https://github.com/modelcontextprotocol/java-sdk/issues/1013),
  abierto, milestone "3.x Planning" sin fecha, prioridad P2 (verificado
  27-jul-2026).
- Spring AI 2.0 (GA 12-jun-2026) tampoco lo implementa, y requeriría además
  un upgrade mayor (Spring Boot 4.1 + Spring Framework 7) — este proyecto usa
  Spring Boot 3.5.7 + Spring AI 1.1.0-M3.
- El protocolo exige que el **cliente** declare la capability
  `io.modelcontextprotocol/tasks` en cada request antes de que el server
  pueda devolver un task — sin soporte de cliente, no se puede usar aunque el
  server lo implemente.

Por eso existe `LongRunningTaskRegistry`: un motor propio que modela los
mismos campos/estados que el `Task` real de SEP-2663, para que el día que el
SDK/los clientes lo soporten, migrar sea reemplazar el "adaptador" de
exposición (cómo se expone al protocolo), no rediseñar el motor.

## El motor (ya construido, no tocar salvo que cambie el diseño)

`com.atina.jdeMCPServer.mcp.tasks`:

- **`TaskStatus`**: `WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELLED`
  (mismos nombres que SEP-2663).
- **`LongRunningTask`**: record con `taskId, status, statusMessage,
  createdAt, lastUpdatedAt, ttlMs, pollIntervalMs, result, error` — forma
  plana, igual que el `Task` real (no anidado bajo una key `"task"`).
- **`LongRunningTaskRegistry`** (`@Component`, bean único compartido entre
  todos los tools que lo usen): `ConcurrentHashMap` en memoria, **sin
  persistencia a propósito** (si el server se reinicia, se pierde el estado y
  el próximo llamado arranca de cero). Indexa por key de negocio y por
  `taskId` (aunque hoy nada busca por `taskId` — se dejó listo para el día
  del adaptador real). Un solo método público:

  ```java
  <T> LongRunningTask getOrStart(String key, Duration hardTimeout, Long pollIntervalMs,
                                  Duration initialWait, Supplier<T> work)
  ```

  Si no hay tarea en curso para `key`, arranca `work` en background y espera
  acotado por `initialWait` (si termina rápido, esa misma llamada ya devuelve
  el resultado final). Si ya hay una en curso, devuelve su estado sin
  arrancar nada nuevo. Limpieza automática: grace period corto tras terminar
  + barrido `@Scheduled` (mismo patrón que `PendingPurchaseOrderStore`).

  Properties del motor (`application.properties`, compartidas por todos los
  tools que lo usen): `jde.mcp.tasks.executor-pool-size`,
  `jde.mcp.tasks.default-poll-interval-ms`,
  `jde.mcp.tasks.completed-grace-period-seconds`,
  `jde.mcp.tasks.cleanup-interval-minutes`.

## Capacidad: cuántas tareas corren en paralelo

El límite real es **`jde.mcp.tasks.executor-pool-size` (default 2)** —
cuántas tareas pueden estar *ejecutando* (corriendo su `Supplier`, es decir,
la llamada bloqueante real al Gateway) al mismo tiempo. Es un pool fijo, **un
solo bean compartido por todos los tools que usan el motor** (hoy
`jde_list_pending_purchase_orders` + `jde_search_items` juntos, no 2 cada
uno) — no hay un pool separado por tool.

Qué pasa al superar ese número: no falla ni se rechaza nada. La tarea
extra se encola (la cola interna del `ExecutorService` es ilimitada) y espera
a que se libere un thread. El caller la ve como "todavía en curso" un poco
más de tiempo, nunca como un error. Tampoco hay límite en la cantidad de
tareas *distintas* que el registry puede tener trackeadas a la vez (eso es
solo memoria, cada `Entry` es liviana) — el límite es únicamente sobre
cuántas corren *simultáneamente*.

Otras capas que existen pero no son el cuello de botella hoy:
- Tomcat: sin `server.tomcat.threads.max` configurado explícitamente →
  default de Spring Boot, 200 threads para requests HTTP entrantes (limita
  cuántas llamadas MCP puede *recibir* el server en general, muy por encima
  de 2).
- Los `WebClient` hacia el Gateway (`JdePurchaseOrderClient`,
  `JdeSalesOrderClient`) usan el connection pool default de Reactor Netty,
  sin `ConnectionProvider` propio configurado — tampoco es el límite
  práctico.

**Pendiente de revisar** (el comentario de `LongRunningTaskRegistry` ya lo
anticipaba: "el pool está dimensionado para un solo consumidor... revisar el
tamaño si se conecta un segundo tool lento"): con `jde_search_items` ya
conectado, el pool de 2 threads es compartido entre dos tools. Si en algún
momento corren simultáneamente 2 consultas lentas de purchase orders, una
búsqueda de items nueva quedaría encolada detrás de ambas aunque sea una
operación más liviana. Si se conecta un tercer tool con este patrón, vale la
pena subir `jde.mcp.tasks.executor-pool-size` (ej. a 4) en vez de dejarlo en 2
por default.

## ⚠️ La regla de oro (por qué esto no es un simple `CompletableFuture.supplyAsync`)

`JdeAuthService.getOrCreateToken()` usa `RequestContextHolder`/`RequestAttributes.SCOPE_REQUEST`
— ThreadLocal, atado al `HttpServletRequest` de Tomcat. El `Supplier<T>` que
se le pasa a `getOrStart` corre en el `ExecutorService` propio del registry,
**sin acceso a ese contexto**, y para cuando corra, el request original ya
puede haber devuelto respuesta.

**Por eso: resolver el token JDE (rápido, no es la parte lenta) SIEMPRE en el
hilo del request original, ANTES de llamar a `getOrStart`, y pasarlo como
dato plano capturado por el `Supplier`.** Nunca resolverlo dentro del propio
`Supplier`. Ver "Paso 1" abajo.

## ⚠️ El otro bug real que ya se encontró una vez (no lo repitas)

Probando en vivo (no lo detectó ningún test unitario) apareció una carrera:
si el trabajo termina *muy* rápido (éxito o error), el `future.get(initialWait, ...)`
acotado dentro de `getOrStart` puede desbloquearse *antes* de que el callback
`whenComplete` (que corre en otro hilo) termine de actualizar el snapshot —
resultado: la primera llamada devolvía "todavía en curso" cuando en realidad
ya había terminado. **Ya está arreglado en el motor** (`getOrStart` construye
el snapshot terminal directo del resultado de su propio `get()`, no confía en
que `whenComplete` ya corrió) — no hace falta hacer nada al respecto al
conectar un tool nuevo, pero si alguna vez tocás `LongRunningTaskRegistry`,
tené esto presente y no lo reintroduzcas.

## Receta para conectar un tool nuevo

### Paso 0 — ¿Este tool lo necesita?

Solo vale la pena para tools que:
- Pegan a JDE/Atina Gateway con una operación que puede tardar mucho (no
  segundos).
- Un cliente MCP real (Slack, etc.) le corta el timeout hoy.

No lo apliques preventivamente a tools rápidos.

### Paso 1 — En el `*Client`/`*Service` (capa de acceso a JDE)

Si el método ya pasa por un `executeGatewayOperation` propio (patrón
`JdePurchaseOrderClient`/`JdeSalesOrderClient`), separarlo así:

1. Extraer el POST real (`gatewayWebClient.post()....block()`) a un helper
   privado `postToGateway(operacionKey, value, token)` que reciba el token
   como parámetro (no lo resuelva).
2. El método `doExecuteGatewayOperation(...)` original sigue igual (resuelve
   token vía `authService.getOrCreateToken()`, llama `postToGateway(...)`,
   llama `authService.updateTokenFromResponse(...)`) — **sin cambio de
   comportamiento** para los callers existentes.
3. Agregar `doExecuteGatewayOperationWithToken(operacionKey, value, token)`
   (usa `postToGateway(...)`, **sin** `updateTokenFromResponse` — no tiene el
   `RequestContextHolder` para saber a qué cache refrescar) y
   `executeGatewayOperationWithToken(...)` (mismo wrap con
   `requestCoalescer.execute(...)` que el original).
4. Exponer un método público para resolver lo que el `Supplier` va a
   necesitar de forma plana:
   - Si el tool necesita solo el token (ej. `jde_search_items`): un método
     simple `resolveSessionToken()` → `authService.getOrCreateToken()`.
   - Si además necesita algo derivado del token (ej.
     `approverAddressNumber` en purchase orders): un record `XyzContext(String token, ...)`
     + `resolveXyzContext()` que resuelve el token UNA vez y deriva el resto
     del mismo token (no volver a llamar `getOrCreateToken()` dos veces).
5. Agregar el método público `xyzWithToken(..., token_o_context)` que llama
   `executeGatewayOperationWithToken(...)` en vez del original — mismo cuerpo
   que el método público existente, solo cambia esa una línea. Si el método
   original hace algo más que solo llamar al Gateway (shaping de resultados,
   aplicar un `limit`, poblar un cache tipo `PendingPurchaseOrderStore`),
   extraer ESO a un helper compartido también, para no duplicarlo entre la
   versión normal y la `WithToken` (ver `JdePurchaseOrderClient.fetchAllPendingOrders`
   como ejemplo de esto — `jde_search_items` no lo necesitó, porque no shapea
   nada, devuelve el string del Gateway tal cual).

### Paso 2 — ¿Hace falta un "limit" o filtros con defaults?

Si el tool tiene un parámetro tipo `limit` que solo afecta cuánto se muestra
(no la llamada real al Gateway), **no lo incluyas en la key de
deduplicación** — se aplicaría *después* de leer el resultado del registry,
no antes. Si el tool tiene parámetros opcionales con default (ej.
`orderTypeCode` con un valor configurado si viene `null`), calculá los
valores "efectivos" con un método reusable (ver
`JdePurchaseOrderClient.resolveEffectiveFilters`) y usá esos valores
defaulteados en la key — así una llamada con el parámetro explícito y una sin
informarlo (que cae al mismo default) comparten la misma tarea, en vez de
disparar dos jobs redundantes al Gateway.

### Paso 3 — En el `@Component` del tool (`*Tools.java`)

1. Inyectar `LongRunningTaskRegistry taskRegistry` + 4 `@Value`:
   - `${jde.<tool>.async.enabled:true}` — kill switch.
   - `${jde.<tool>.async.initial-wait-seconds:8}`.
   - `${jde.atina.gateway.timeout-minutes:10}` — para el `hardTimeout` (+1 min de margen).
   - `${jde.mcp.tasks.default-poll-interval-ms:5000}` — reusa la property del motor, no crear una nueva por tool.
2. Al principio del método del tool: `if (!enabled) { /* comportamiento síncrono original, sin tocar */ }`.
   Guardar el código síncrono original TAL CUAL en esta rama — es el kill
   switch, tiene que seguir andando exactamente igual que antes de este
   cambio.
3. Si `enabled`: resolver el token/contexto (Paso 1.4) **en este punto,
   síncrono** (regla de oro de arriba); construir la key de negocio (prefijo
   corto + los parámetros relevantes, ver Paso 2); llamar
   `taskRegistry.getOrStart(key, Duration.ofMinutes(gatewayTimeoutMinutes + 1), defaultPollIntervalMs, Duration.ofSeconds(initialWaitSeconds), () -> client.xyzWithToken(..., ctx))`.
4. `switch` sobre `task.status()`:
   - `WORKING, INPUT_REQUIRED` → texto indicando que sigue en curso y que hay
     que volver a llamar al mismo tool con los mismos parámetros (incluir
     `task.pollIntervalMs()` como sugerencia de espera). No es un error.
   - `COMPLETED` → formatear `task.result()` (castear al tipo que devuelve el
     `Supplier`) igual que la rama síncrona.
   - `FAILED` → mismo formato de mensaje de error que ya usaba el tool antes
     (para no cambiar el tono/UX), usando `task.error()`.
   - `CANCELLED` → texto genérico corto (rama sin uso real hoy, ningún tool
     cancela nada todavía, pero hay que cubrirla porque el `switch` es
     exhaustivo sobre el enum).
5. Actualizar la descripción `@McpTool`: agregar un párrafo explícito
   diciéndole al LLM que si la respuesta dice "todavía en curso", vuelva a
   llamar al mismo tool en vez de reportarlo como error.

### Paso 4 — Properties

```properties
# jde_<tool_name> usa el motor generico de tareas largas (jde.mcp.tasks.*, ya
# existente) -- no requiere properties nuevas del motor, solo estas dos.
jde.<tool>.async.enabled=true
jde.<tool>.async.initial-wait-seconds=8
```

### Paso 5 — Verificar

1. `./mvnw compile && ./mvnw test` (no hace falta agregar tests nuevos de
   `LongRunningTaskRegistry` — ya están cubiertos; sí conviene un test rápido
   si el `*Client` tiene lógica propia no trivial, como
   `resolveEffectiveFilters`).
2. `docker/scripts/up-local.sh mcp-server` + llamar al tool real vía
   `curl`/Postman con un token de Atina (ver `docker/scripts/validate-local.sh`
   para el patrón de armar uno) — confirmar que:
   - La primera llamada responde rápido (no espera el `hardTimeout` completo).
   - El estado devuelto es el correcto ya en esa primera llamada (no queda
     "WORKING" obsoleto si en realidad ya terminó — este es exactamente el
     bug que ya se encontró una vez, ver arriba).
   - Con `<tool>.async.enabled=false`, se reproduce el comportamiento
     síncrono/bloqueante de siempre.
3. `docker/scripts/validate-local.sh` sin regresión.

## Ejemplos ya implementados (referencia concreta)

| Tool | Client | Key de negocio | ¿Necesitó `limit`/filtros efectivos? | ¿Necesitó más que el token? |
|---|---|---|---|---|
| `jde_list_pending_purchase_orders` | `JdePurchaseOrderClient` | `"pending-orders\|" + approverAddressNumber + "\|" + orderType + "\|" + businessUnit + "\|" + statusCodeNext` | Sí — `resolveEffectiveFilters` + `fetchAllPendingOrders`/`limitAndFormatPendingOrders` separados | Sí — `ApproverContext(token, approverAddressNumber)`, porque el resultado depende de quién pregunta |
| `jde_search_items` | `JdeSalesOrderClient` | `"item-search\|" + searchText` | No — el resultado no depende del caller, solo del texto buscado; no hay `limit` que aplicar después | No — el resultado no depende del approver, alcanza con `resolveSessionToken()` |

`jde_search_items` fue más simple de conectar precisamente porque su
resultado no es approver-específico — vale la pena, al encarar un tool
nuevo, preguntarse primero si el resultado depende de la identidad de quien
llama (necesita algo tipo `ApproverContext`) o no (alcanza con el token
solo), y si tiene un parámetro de presentación tipo `limit` que conviene
sacar de la key.

## Fuera de alcance de este patrón (a propósito)

- No se generalizó a un tool de cancelación (`CANCELLED` está modelado mas no
  conectado a nada) ni a elicitation intermedia (`INPUT_REQUIRED` idem).
- No se armó el adaptador real de SEP-2663 — este documento es exactamente
  la preparación para cuando eso sea posible.
