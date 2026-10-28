# ADR 0005 — Eventos de estado final via outbox transacional

**Status:** aceita

## Contexto

Consumidores precisam saber quando um pedido termina. Exigir *polling* em
`GET /api/v1/requests/{id}` gera carga e atraso. Publicar direto no broker
dentro do processamento cria escrita dupla: o banco pode confirmar e o broker
falhar, ou o contrário.

## Decisão

- Tabela `outbox_event` (migração `V2`). Ao chegar em `COMPLETED` ou `FAILED`,
  `ActivationProcessor` grava o evento na mesma transação que muda o estado.
- Tipos: `activation.completed` e `activation.failed`. O payload JSON traz
  `requestId`, `externalId`, `clientId`, `customerId`, `productCode`, `status`,
  `attempts`, `failureReason` e `occurredAt`.
- `OutboxRelay` (agendado a cada 1 s, `app.outbox.*`) lê os eventos não
  publicados em ordem de criação, publica pela porta `EventPublisher` e só
  então marca `published_at`. Uma falha interrompe o lote para manter a ordem.
- Entrega **pelo menos uma vez**: queda entre publicar e marcar, ou duas
  réplicas lendo o mesmo lote, repetem o evento. Consumidores deduplicam pelo
  `id` do evento, que também deve ser a chave da mensagem no broker.
- O adaptador atual, `LoggingEventPublisher`, só registra os metadados (o
  payload tem dados do cliente). Kafka ou SNS entram como outra implementação
  de `EventPublisher`, sem mudar o domínio.

## Consequências

- Um job horário apaga eventos publicados há mais de 7 dias
  (`app.outbox.retention`).
- O relay lê o lote com `FOR UPDATE SKIP LOCKED` e publica dentro da
  transação, então réplicas não publicam os mesmos eventos. A transação fica
  aberta durante a publicação; com broker real, o lote deve ser pequeno e o
  timeout do produtor curto.
