# ADR 0004 — Circuit breaker no provedor

**Status:** aceita (revê a alternativa descartada na ADR 0003)

## Contexto

Com o provedor fora do ar, cada pedido gasta suas 5 tentativas em erros
temporários e termina em `FAILED`, mesmo que o provedor volte em minutos. O
backoff persistido espaça as chamadas de um pedido, mas não impede que muitos
pedidos batam ao mesmo tempo num provedor já indisponível.

## Decisão

- Resilience4j `CircuitBreaker` (somente o módulo core, sem AOP), aplicado em
  `HttpActivationProvider`.
- Conta como falha apenas o que é `TransientFailure` (5xx, 408, 429, timeout,
  rede). Respostas 4xx definitivas contam como sucesso: o provedor está
  saudável, o pedido é que é inválido.
- Padrões: janela de 20 chamadas, mínimo de 10, abre com 50% de falhas, fica
  aberto 30 s e permite 3 chamadas em meio-aberto. Configurável em
  `app.provider.circuit-breaker.*`.
- Com o circuito aberto, `ActivationWorker` não faz a varredura. Os pedidos
  continuam `PENDING` e não gastam tentativas. A transição de aberto para
  meio-aberto é automática, porque nenhuma chamada a dispararia.
- Se o circuito abrir no meio de um lote, a chamada curto-circuitada vira
  `TransientFailure` e segue o backoff normal.
- Estado e contadores aparecem em `/actuator/prometheus`
  (`resilience4j_circuitbreaker_*`).

## Consequências

- O estado é por instância. Com várias réplicas, cada uma abre o próprio
  circuito; isso basta para proteger o provedor sem coordenação.
- Com o worker concorrente, um bulkhead Resilience4j limita as chamadas
  simultâneas ao provedor (padrão 4 por instância, espera de até 1 s).
