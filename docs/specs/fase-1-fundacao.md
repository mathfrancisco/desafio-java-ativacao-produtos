# Fase 1 — Fundação

**Objetivo:** aplicação Spring Boot inicializável, com build reproduzível e
modelo de domínio com transições de estado testadas.

**Pronto quando:** `./mvnw verify` passa em um clone limpo e
`/actuator/health` responde `UP`.

---

## Card 1.1 — Projeto Maven e wrapper

- **Dependências:** nenhuma.
- **Arquivos:** `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`,
  `ActivationApplication.java`.
- **Passos:**
  1. Criar `pom.xml` com parent `spring-boot-starter-parent` 3.5.x e Java 21.
  2. Declarar starters web, validation, data-jpa, oauth2-resource-server,
     actuator; Flyway, PostgreSQL, Micrometer Prometheus.
  3. Declarar dependências de teste: starter-test, spring-security-test, H2,
     WireMock, Testcontainers, ArchUnit.
  4. Gerar o Maven Wrapper 3.9.11.
- **Critérios de aceite:**
  - `./mvnw -v` mostra Maven 3.9.11 e Java 21.
  - `./mvnw verify` compila e executa os testes.
- **Cenários negativos:** build com Java 17 falha com mensagem clara do
  `maven-compiler-plugin` (`release 21`).
- **Validação:** `./mvnw -q verify`.

## Card 1.2 — Configuração externa

- **Dependências:** 1.1.
- **Arquivos:** `src/main/resources/application.yml`.
- **Passos:**
  1. Ler datasource de `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
  2. Ler provedor de `PROVIDER_BASE_URL`, timeouts e retry de
     `app.provider.*`.
  3. Ler JWT de `JWT_ISSUER_URI` / `JWT_AUDIENCE` ou `JWT_DEV_SECRET`.
  4. `spring.jpa.open-in-view=false`, `ddl-auto=validate`, Jackson com datas
     ISO-8601, `server.shutdown=graceful`.
- **Critérios de aceite:** nenhum segredo fixo para produção no repositório;
  valores padrão servem só para desenvolvimento local.
- **Cenários negativos:** banco indisponível derruba a inicialização com erro
  explícito do Hikari.

## Card 1.3 — Domínio e transições de estado

- **Dependências:** 1.1.
- **Arquivos:** `domain/ActivationRequest.java`, `domain/ActivationStatus.java`,
  `ActivationRequestTest.java`.
- **Passos:**
  1. `ActivationStatus` com `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` e
     `isFinal()`.
  2. Entidade com fábrica `ActivationRequest.create(...)` e métodos de
     transição: `startProcessing`, `complete`, `scheduleRetry`, `fail`.
  3. `@Version` para lock otimista; datas `Instant` UTC.
- **Critérios de aceite:**
  - Pedido novo é `PENDING`, `attempts = 0`, `next_attempt_at = createdAt`.
  - `complete()` em `PENDING` lança `IllegalStateException`.
  - Estados finais não aceitam nenhuma transição.
- **Validação:** `ActivationRequestTest`.
