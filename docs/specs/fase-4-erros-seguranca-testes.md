# Fase 4 — Erros, segurança e testes

**Objetivo:** respostas de erro seguras e padronizadas, endpoints protegidos
por JWT com escopos e cobertura dos testes obrigatórios e diferenciais.

**Pronto quando:** todos os testes obrigatórios do enunciado passam e os
testes de `401`/`403`/BOLA estão verdes.

---

## Card 4.1 — Problem Details e correlação

- **Dependências:** 2.4.
- **Arquivos:** `api/error/ApiExceptionHandler.java`,
  `infrastructure/web/CorrelationIdFilter.java`.
- **Passos:**
  1. `@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`.
  2. Mapear validação (erros por campo), JSON malformado, tipo de parâmetro,
     não encontrado, duplicidade, corpo grande e erro inesperado.
  3. Filtro que aceita/gera `X-Correlation-Id`, grava no MDC e devolve na
     resposta.
- **Critérios de aceite:** nenhuma resposta contém stack trace, nome de classe
  ou SQL; erro inesperado responde `500` com detalhe genérico e é logado com o
  `correlationId`.

## Card 4.2 — Resource Server JWT

- **Dependências:** 2.4.
- **Arquivos:** `infrastructure/security/SecurityConfig.java`,
  `infrastructure/security/ClientIdentity.java`, `scripts/dev-token.sh`.
- **Passos:**
  1. `POST` exige `SCOPE_activation:write`; `GET` exige `SCOPE_activation:read`.
  2. Produção: `issuer-uri` (chaves via JWKS) + validação de audiência.
  3. Desenvolvimento: segredo HS256 de `JWT_DEV_SECRET`, com script para gerar
     token local.
  4. API sem sessão, sem CSRF; Actuator `health`/`prometheus` públicos.
  5. Identidade do cliente = claim `azp`, ou `sub` se ausente.
- **Critérios de aceite:** sem token `401`; escopo errado `403`; recurso de
  outro cliente `404`.

## Card 4.3 — Testes obrigatórios e diferenciais

- **Dependências:** 4.1, 4.2, 3.3.
- **Arquivos:** `src/test/java/**`.
- **Cobertura:**
  - Criação válida (`201` + `Location`) e inválida (`400` por campo).
  - Duplicidade sequencial (mesmo conteúdo `200`, divergente `409`) e
    concorrente em PostgreSQL real (Testcontainers; ignorado sem Docker).
  - Consulta existente, inexistente, de outro cliente e id malformado.
  - Integração: sucesso, 400, 500 com retry, timeout.
  - Transições de domínio e política de tentativas.
  - ArchUnit: domínio isolado.
- **Validação:** `./mvnw verify`.
