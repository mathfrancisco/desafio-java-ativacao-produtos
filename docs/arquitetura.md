# Arquitetura

## Visão geral

Um único serviço Spring Boot recebe solicitações de ativação, persiste cada
pedido no PostgreSQL e responde imediatamente com `201 Created`. Um worker
interno, executado na mesma aplicação, lê os pedidos pendentes da própria
tabela (padrão *outbox* / fila persistida no banco) e chama o provedor externo
fora de qualquer transação de banco.

```
 Cliente (sistema)                        Serviço de ativação                        Provedor
 ───────────────── ── JWT (OAuth 2.0) ──▶ ┌──────────────────────────────┐
  POST /api/v1/requests                   │ API (Spring MVC)             │
  GET  /api/v1/requests/{id}              │   └─ ActivationRequestService│
  GET  /api/v1/requests?filtros           │         │ INSERT (unique)    │
                                          │         ▼                    │
                                          │  PostgreSQL activation_request│
                                          │         ▲  claim / resultado │
                                          │         │                    │
                                          │ ActivationWorker (@Scheduled)│── POST /provider/v1/activate ─▶
                                          └──────────────────────────────┘    Idempotency-Key: externalId
```

Por que não chamar o provedor dentro do `POST`:

- A latência e as falhas do provedor não afetam a criação do pedido.
- O pedido fica persistido antes de qualquer efeito externo; se o processo
  cair, o worker retoma o pedido (não há perda).
- Não há transação de banco aberta durante a chamada HTTP.

Por que não usar broker (Kafka/RabbitMQ): o volume do desafio não justifica
outro componente. A tabela já é a fonte de verdade e serve como fila durável.
A troca por um broker está descrita em melhorias futuras.

## Dependências escolhidas

| Dependência | Motivo |
|---|---|
| Java 21 | LTS, `record`, `switch` com padrões. |
| Spring Boot 3.5.x | Versão 3.x estável exigida como diferencial. |
| `spring-boot-starter-web` | API REST com Spring MVC. |
| `spring-boot-starter-validation` | Bean Validation nos DTOs de entrada. |
| `spring-boot-starter-data-jpa` | Persistência com Hibernate. |
| `spring-boot-starter-oauth2-resource-server` | Validação de JWT e escopos. |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | Health (liveness/readiness) e métricas. |
| PostgreSQL 17 + driver `postgresql` | Banco relacional de execução. |
| Flyway (`flyway-core`, `flyway-database-postgresql`) | Migrações versionadas. |
| `RestClient` (Spring 6) com `JdkClientHttpRequestFactory` | Cliente HTTP síncrono com timeouts de conexão e leitura; sem dependência extra. |
| `resilience4j-circuitbreaker` + `resilience4j-micrometer` | Circuit breaker do provedor e suas métricas (ADR 0004). |
| `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | Traces e propagação W3C; exportação OTLP opcional. |
| `resilience4j-bulkhead` | Limite de chamadas simultâneas ao provedor. |
| `datasource-micrometer-spring-boot` | Spans e métricas de JDBC (conexão, query). |
| `bucket4j_jdk17-core` + Caffeine | Rate limit por cliente em memória, com expiração dos baldes. |
| H2 (somente testes) | Testes rápidos sem Docker. |
| Testcontainers PostgreSQL (somente testes) | Teste de concorrência contra banco real; ignorado sem Docker. |
| WireMock (somente testes) | Simula o provedor: sucesso, 400, 500, 503 e timeout. |
| `spring-security-test` | Testes de `401` e `403`. |
| ArchUnit (somente testes) | Garante que o domínio não depende da API nem da infraestrutura. |

Ficaram fora, por não serem necessários: Lombok (records e código explícito
bastam), MapStruct (mapeamento em uma linha), Resilience4j Retry (retry/backoff são
resolvidos pelo agendamento persistido; ver ADR 0003).

## Estrutura do projeto

```
src/main/java/com/desafio/activation
├── ActivationApplication.java
├── api                      # Camada HTTP: controllers, DTOs, erros
│   ├── ActivationRequestController.java
│   ├── AuthenticatedClient.java  # identidade do cliente a partir do JWT
│   ├── dto/                 # CreateActivationRequest, ActivationRequestResponse, PageResponse
│   └── error/               # ApiExceptionHandler (Problem Details)
├── application              # Casos de uso e orquestração
│   ├── ActivationRequestService.java
│   ├── ActivationProcessor.java
│   ├── ActivationWorker.java
│   ├── ProcessingProperties.java
│   ├── RetryPolicy.java
│   └── exceptions/          # DuplicateExternalIdException, RequestNotFoundException
├── domain                   # Modelo, regras e portas
│   ├── ActivationRequest.java
│   ├── ActivationStatus.java
│   ├── ActivationRequestRepository.java  # porta de persistência (Spring Data)
│   ├── ActivationProvider.java           # porta para o provedor
│   ├── ActivationCommand.java
│   └── ProviderResult.java
└── infrastructure
    ├── provider/            # HttpActivationProvider (RestClient) + propriedades
    ├── security/            # SecurityConfig, JwtProperties
    └── web/                 # CorrelationIdFilter, RequestSizeLimitFilter
src/main/resources
├── application.yml
└── db/migration/V1__create_activation_request.sql
```

Regra de dependência: `api → application → domain ← infrastructure`. O domínio
não importa Spring MVC, segurança nem HTTP; a aplicação não conhece a API nem
os adaptadores; a API não acessa repositórios. As regras são verificadas por
ArchUnit (`ArchitectureTest`). O repositório Spring Data fica no domínio como
porta de persistência, uma concessão pragmática para não duplicar interfaces.

## Modelagem de dados

Tabela `activation_request`:

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `uuid` PK | Gerado pela aplicação; não sequencial (evita enumeração). |
| `external_id` | `varchar(64)` | `UNIQUE` — garantia real contra duplicidade. |
| `client_id` | `varchar(128)` | Sistema dono do pedido (claim `sub`/`azp` do JWT). |
| `customer_id` | `varchar(64)` | Obrigatório. |
| `product_code` | `varchar(64)` | Obrigatório. |
| `status` | `varchar(16)` | `CHECK` em `PENDING, PROCESSING, COMPLETED, FAILED`. |
| `attempts` | `int` | Tentativas já iniciadas; também é o *fencing token* do worker. |
| `next_attempt_at` | `timestamptz` | Quando o pedido pode ser processado (backoff). |
| `locked_until` | `timestamptz` | Fim da reserva do worker em `PROCESSING`. |
| `failure_reason` | `varchar(255)` | Motivo sanitizado da falha final. |
| `version` | `bigint` | Lock otimista (JPA `@Version`). |
| `created_at`, `updated_at` | `timestamptz` | UTC. |

Índices e justificativa:

- `uk_activation_request_external_id (external_id)`: unicidade e busca por
  repetição.
- `ix_activation_request_due (status, next_attempt_at)`: consulta do worker
  pelos pendentes vencidos e pelos `PROCESSING` com reserva expirada.
- `ix_activation_request_client_status (client_id, status, created_at)` e
  `ix_activation_request_client_customer (client_id, customer_id, created_at)`:
  listagem filtrada e paginada, sempre escopada ao cliente e ordenada por data.

Tabela `outbox_event` (ADR 0005):

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `uuid` PK | Id do evento; chave de deduplicação do consumidor. |
| `aggregate_id` | `uuid` | Pedido que gerou o evento. |
| `event_type` | `varchar(64)` | `activation.completed` ou `activation.failed`. |
| `payload` | `varchar(4000)` | JSON do estado final. |
| `created_at` | `timestamptz` | Ordem de publicação. |
| `published_at` | `timestamptz` | Nulo até o relay publicar. |

Índices: `ix_outbox_event_unpublished (published_at, created_at)` para o relay
e `ix_outbox_event_aggregate (aggregate_id, created_at)` para consulta por
pedido.

### Máquina de estados

```
PENDING ──claim──▶ PROCESSING ──2xx──────────────▶ COMPLETED
   ▲                   │──4xx definitivo──────────▶ FAILED
   └──retry (backoff)──┤──5xx/429/timeout, tentativas restantes
                       └──5xx/429/timeout, sem tentativas──▶ FAILED
PROCESSING com reserva expirada ──reconciliação──▶ novo claim (mesma chave de idempotência)
```

As transições ficam em `ActivationRequest` (`startProcessing`, `complete`,
`scheduleRetry`, `fail`); qualquer transição inválida gera
`IllegalStateException`. O worker reserva e grava o resultado por meio desses
métodos, com `@Version` (lock otimista) protegendo cada gravação e o número da
tentativa servindo de *fencing token*.

## Design da API

Base: `/api/v1`. JSON, datas em ISO-8601 UTC (`2026-10-28T18:00:00Z`).

### `POST /api/v1/requests` — escopo `activation:write`

Entrada:

```json
{ "externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500" }
```

Validação: os três campos são obrigatórios, com 1 a 64 caracteres no padrão
`^[A-Za-z0-9._:-]+$`. Corpo acima de 16 KB é rejeitado.

| Situação | Resposta |
|---|---|
| Novo pedido | `201 Created`, `Location: /api/v1/requests/{id}`, corpo do recurso. |
| Mesmo `externalId`, mesmo conteúdo, mesmo cliente | `200 OK` com o recurso existente (repetição idempotente). |
| Mesmo `externalId`, conteúdo ou cliente diferente | `409 Conflict`. |
| Dados inválidos / JSON malformado | `400 Bad Request` com erros por campo. |

### `GET /api/v1/requests/{id}` — escopo `activation:read`

`200 OK` com o recurso; `404 Not Found` se não existe ou pertence a outro
cliente (não revela a existência — proteção contra BOLA). `id` que não é UUID
retorna `400`.

### `GET /api/v1/requests?status=&customerId=&page=0&size=20`

Lista paginada do cliente autenticado, ordenada por `createdAt` decrescente.
`size` entre 1 e 100.

Recurso:

```json
{
  "id": "0b8c3f7e-1f7a-4a8d-9a53-3a3c1c0f9b11",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "attempts": 0,
  "failureReason": null,
  "createdAt": "2026-10-28T18:00:00Z",
  "updatedAt": "2026-10-28T18:00:00Z"
}
```

## Design system da API

Como o produto é uma API sem interface gráfica, o "design system" é o conjunto
de convenções que todo endpoint segue:

- **Nomes:** recursos no plural (`requests`), campos em `camelCase`, enums em
  `UPPER_SNAKE_CASE`, versão no caminho (`/v1`).
- **Erros:** sempre `application/problem+json` (RFC 9457) com `type`, `title`,
  `status`, `detail`, `instance`, `correlationId` e, em validação, `errors`
  (`[{ "field", "message" }]`). Nunca stack trace, SQL ou nome de classe.

  | Código | `type` | Quando |
  |---|---|---|
  | 400 | `/problems/validation` | Campo inválido, JSON malformado, parâmetro inválido. |
  | 401 | — (cabeçalho `WWW-Authenticate`) | Token ausente ou inválido. |
  | 403 | — | Token sem o escopo exigido. |
  | 404 | `/problems/not-found` | Recurso inexistente ou de outro cliente. |
  | 409 | `/problems/duplicate-external-id` | `externalId` repetido com conteúdo divergente. |
  | 413 | `/problems/payload-too-large` | Corpo acima do limite. |
  | 500 | `/problems/internal` | Erro inesperado; detalhe genérico. |

- **Correlação:** cabeçalho `X-Correlation-Id` aceito (até 64 caracteres
  seguros) ou gerado, devolvido na resposta, gravado no MDC dos logs e no
  campo `correlationId` dos erros.
- **Datas:** `Instant` em UTC.
- **Paginação:** `{ "content": [...], "page", "size", "totalElements", "totalPages" }`.
- **Segurança:** todo endpoint de negócio exige Bearer JWT; escopos
  `activation:read` e `activation:write`. Actuator expõe só `health` e
  `prometheus`.

## Decisões arquiteturais

Registradas em [docs/adr](adr):

- [0001 — Fila persistida na própria tabela](adr/0001-fila-persistida-no-banco.md)
- [0002 — Duplicidade por restrição única e semântica de repetição](adr/0002-duplicidade-externalid.md)
- [0003 — Política de integração e tentativas](adr/0003-politica-integracao.md)
- [0004 — Circuit breaker no provedor](adr/0004-circuit-breaker-provedor.md)
- [0005 — Eventos de estado final via outbox transacional](adr/0005-eventos-outbox.md)
