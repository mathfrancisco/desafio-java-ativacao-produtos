# Fase 2 — Persistência e API

**Objetivo:** criar e consultar solicitações com unicidade garantida pelo banco.

**Pronto quando:** `POST` e `GET` funcionam conforme
[arquitetura.md](../arquitetura.md#design-da-api) e o teste de duplicidade
concorrente passa.

---

## Card 2.1 — Migração Flyway

- **Dependências:** 1.3.
- **Arquivos:** `db/migration/V1__create_activation_request.sql`.
- **Passos:** criar tabela, `CHECK` de status, `UNIQUE (external_id)` e os três
  índices descritos na modelagem.
- **Critérios de aceite:** Hibernate com `ddl-auto=validate` inicia sem erro em
  H2 (modo PostgreSQL) e em PostgreSQL 17.
- **Cenários negativos:** `INSERT` com status desconhecido é rejeitado pelo
  `CHECK`.

## Card 2.2 — Repositório

- **Dependências:** 2.1.
- **Arquivos:** `infrastructure/persistence/ActivationRequestRepository.java`.
- **Passos:**
  1. `findByExternalId`, `findByIdAndClientId`.
  2. Busca paginada por `clientId` com filtros opcionais `status` e
     `customerId`.
  3. `findDueIds(now, limit)` para o worker.
  4. `claim(id, version, now, lockedUntil)` e `applyResult(...)` como `UPDATE`
     condicionais (`@Modifying`).
- **Critérios de aceite:** `claim` retorna 1 para o primeiro chamador e 0 para
  uma versão desatualizada.

## Card 2.3 — Caso de uso de criação com semântica de repetição

- **Dependências:** 2.2.
- **Arquivos:** `application/ActivationRequestService.java`,
  `application/exceptions/*`.
- **Passos:**
  1. Atalho: se já existe o `externalId`, comparar e responder.
  2. Senão `saveAndFlush`; em `DataIntegrityViolationException`, reler e
     comparar.
  3. Mesmo conteúdo e cliente: devolver existente marcado como `replayed`.
  4. Conteúdo divergente: `DuplicateExternalIdException`.
- **Critérios de aceite:** ver [ADR 0002](../adr/0002-duplicidade-externalid.md).
- **Cenários negativos:** duas threads com o mesmo payload produzem 1 registro;
  com payloads diferentes, uma recebe `409`.

## Card 2.4 — Endpoints REST e DTOs

- **Dependências:** 2.3.
- **Arquivos:** `api/ActivationRequestController.java`, `api/dto/*`.
- **Passos:**
  1. `POST` retorna `201` + `Location` ou `200` em repetição.
  2. `GET /{id}` escopado ao cliente autenticado.
  3. `GET` com filtros e paginação (`size` 1..100).
  4. DTOs `record` separados da entidade.
- **Critérios de aceite:** contrato idêntico ao documentado; datas UTC
  terminadas em `Z`.
- **Validação:** `ActivationRequestApiTest`.
