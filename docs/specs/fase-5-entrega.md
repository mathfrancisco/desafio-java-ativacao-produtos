# Fase 5 — Entrega

**Objetivo:** executar a solução com um comando e documentar decisões,
operação em produção e limitações.

**Pronto quando:** `docker compose up --build` sobe aplicação, banco e mock do
provedor; um pedido criado chega a `COMPLETED`; o README responde a todos os
itens do enunciado.

---

## Card 5.1 — Dockerfile

- **Dependências:** fase 4.
- **Arquivos:** `Dockerfile`, `.dockerignore`.
- **Passos:** build em estágios (JDK para compilar, JRE para executar),
  usuário não-root, `-XX:MaxRAMPercentage=75`, `HEALTHCHECK` no
  `/actuator/health/readiness`.
- **Critérios de aceite:** imagem final sem código-fonte nem Maven; processo
  roda com UID não-root.

## Card 5.2 — Docker Compose e mock do provedor

- **Dependências:** 5.1.
- **Arquivos:** `docker-compose.yml`, `provider-mock/mappings/*.json`.
- **Passos:** PostgreSQL 17 com healthcheck, WireMock com cenários
  determinados pelo `productCode` (`FAIL-*` → 400, `UNSTABLE-*` → 503,
  `SLOW-*` → atraso de 10 s, demais → 200), aplicação dependente de ambos.
- **Critérios de aceite:** `docker compose up --build` sem passos manuais.

## Card 5.3 — CI

- **Dependências:** fase 4.
- **Arquivos:** `.github/workflows/ci.yml`.
- **Passos:** checkout, Temurin 21 com cache Maven, `./mvnw -B verify`, build
  da imagem Docker.
- **Critérios de aceite:** pipeline verde no push e no pull request.

## Card 5.4 — README final

- **Dependências:** 5.1–5.3.
- **Conteúdo:** execução a partir de clone limpo, testes, decisões técnicas
  (duplicidade, integração, segurança), análise do cenário de lentidão,
  execução em produção, pendências e melhorias futuras.
- **Critérios de aceite:** todo item de [requisitos.md](../requisitos.md)
  tem resposta ou está listado como não implementado.
