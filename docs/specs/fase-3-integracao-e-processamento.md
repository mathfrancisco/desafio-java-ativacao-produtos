# Fase 3 — Integração e processamento

**Objetivo:** processar pedidos pendentes chamando o provedor com timeouts,
tentativas limitadas e proteção contra workers concorrentes.

**Pronto quando:** testes com WireMock cobrem sucesso, 400, 500/503 e timeout,
e o pedido chega ao estado esperado em cada caso.

---

## Card 3.1 — Cliente HTTP do provedor

- **Dependências:** 1.2.
- **Arquivos:** `domain/ActivationProvider.java`, `domain/ProviderResult.java`,
  `infrastructure/provider/HttpActivationProvider.java`,
  `infrastructure/provider/ProviderProperties.java`.
- **Passos:**
  1. Porta `ActivationProvider.activate(command)` no domínio.
  2. Implementação com `RestClient` e `JdkClientHttpRequestFactory`
     (timeouts de conexão e leitura).
  3. Enviar `Idempotency-Key` e `X-Correlation-Id`.
  4. Classificar a resposta em `Success`, `PermanentFailure` ou
     `TransientFailure`, conforme [ADR 0003](../adr/0003-politica-integracao.md).
- **Critérios de aceite:** nenhuma exceção HTTP escapa do adaptador; o corpo
  de erro do provedor não é repassado ao consumidor da API.
- **Cenários negativos:** provedor fora do ar (conexão recusada) vira
  `TransientFailure`.

## Card 3.2 — Política de tentativas

- **Dependências:** 3.1.
- **Arquivos:** `application/RetryPolicy.java`, `RetryPolicyTest.java`.
- **Passos:** backoff exponencial com *jitter* total, teto configurável e
  limite de tentativas.
- **Critérios de aceite:** atraso sempre em `[0, min(max, base * 2^(n-1))]`;
  `canRetry(n)` falso quando `n >= maxAttempts`.

## Card 3.3 — Processador e worker

- **Dependências:** 2.2, 3.1, 3.2.
- **Arquivos:** `application/ActivationProcessor.java`,
  `application/ActivationWorker.java`.
- **Passos:**
  1. Worker `@Scheduled(fixedDelay)` busca ids vencidos e chama o processador.
  2. Processador reserva (claim), chama o provedor sem transação e aplica o
     resultado com *fencing token* (`attempts`).
  3. Reserva expirada é retomada (reconciliação de `PROCESSING`).
  4. Worker pode ser desligado por `app.worker.enabled=false`.
- **Critérios de aceite:**
  - 2xx: `COMPLETED`.
  - 400: `FAILED` na primeira tentativa.
  - 500/timeout: volta a `PENDING` com `next_attempt_at` futuro; após o
    limite, `FAILED`.
  - Resultado de worker com reserva expirada é descartado.
- **Validação:** `ActivationProcessorIntegrationTest`.
