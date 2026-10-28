# ADR 0001 — Fila persistida na própria tabela

**Status:** aceita

## Contexto

A ativação depende de um provedor externo lento e sujeito a falhas. Chamar o
provedor dentro do `POST` acopla a latência da API à do provedor e mantém uma
transação aberta durante a chamada remota. Se o processo cair depois de gravar
o pedido e antes de chamar o provedor, o pedido ficaria esquecido.

## Decisão

A tabela `activation_request` é a fila. O `POST` só grava o pedido como
`PENDING`. Um `ActivationWorker` agendado:

1. Seleciona um lote de ids vencidos: `PENDING` com `next_attempt_at <= agora`
   ou `PROCESSING` com `locked_until < agora` (reserva expirada).
2. Reserva cada id com um `UPDATE ... WHERE id = ? AND version = ?` condicional
   (lock otimista). Apenas uma instância consegue a reserva; as outras recebem
   zero linhas afetadas e seguem.
3. Chama o provedor sem transação aberta.
4. Grava o resultado com outro `UPDATE` condicionado a `status = PROCESSING` e
   `attempts = <tentativa reservada>`. Esse número funciona como *fencing
   token*: um worker antigo, cuja reserva expirou e foi retomada por outro, não
   sobrescreve o resultado mais novo.

## Consequências

- Funciona com várias instâncias sem coordenador externo.
- Latência de início do processamento = intervalo do agendamento (1 s padrão).
- Em volume alto a consulta de pendentes vira gargalo; a evolução é usar
  `FOR UPDATE SKIP LOCKED` (específico do PostgreSQL) ou um broker.
