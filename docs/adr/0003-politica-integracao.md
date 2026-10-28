# ADR 0003 — Política de integração e tentativas

**Status:** aceita

## Contexto

O provedor `POST /provider/v1/activate` pode responder sucesso, erro de
cliente, erro próprio ou não responder a tempo. Timeout é ambíguo: a ativação
pode ter ocorrido.

## Decisão

Cada chamada envia `Idempotency-Key: <externalId>`. Presume-se que o provedor
deduplica por essa chave; sem esse suporte não há garantia de efeito externo
exatamente uma vez (ver README).

| Resposta do provedor | Classificação | Ação |
|---|---|---|
| 2xx | sucesso | `COMPLETED` |
| 400, 401, 403, 404, 409, 422 e demais 4xx | erro definitivo | `FAILED` sem nova tentativa |
| 408, 429 | erro temporário | nova tentativa |
| 5xx | erro temporário | nova tentativa |
| timeout de conexão/leitura, falha de rede | resultado desconhecido | nova tentativa com a mesma chave |

- Limite padrão: 5 tentativas (`app.provider.retry.max-attempts`).
- Backoff exponencial com *jitter* total: atraso aleatório entre 0 e
  `min(maxDelay, baseDelay * 2^(tentativa-1))`. Padrão: base 2 s, teto 5 min.
- Esgotado o limite, o pedido vai a `FAILED` com motivo sanitizado.
- Timeouts configuráveis: conexão 2 s, leitura 5 s.
- Reserva (`locked_until`) de 30 s, maior que o timeout total, para que a
  reconciliação só retome pedidos realmente abandonados.

## Alternativas descartadas

- Resilience4j Retry em memória: perde o estado se o processo cair e prende uma
  thread durante a espera. O agendamento persistido resolve os dois pontos.
- Circuit breaker: útil com muito tráfego; aqui o backoff persistido já reduz a
  pressão sobre o provedor. Fica como melhoria futura.
