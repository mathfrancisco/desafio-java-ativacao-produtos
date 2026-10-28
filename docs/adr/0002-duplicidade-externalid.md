# ADR 0002 — Duplicidade por restrição única e semântica de repetição

**Status:** aceita

## Contexto

Verificar se o `externalId` existe antes de inserir não protege contra duas
requisições concorrentes: ambas podem ver "não existe" e inserir.

## Decisão

- A coluna `external_id` tem restrição `UNIQUE` no banco. Ela é a garantia.
- O serviço tenta inserir (`saveAndFlush` em transação própria). Em
  `DataIntegrityViolationException` na restrição, relê o registro vencedor.
- Comparação com o registro existente:
  - mesmo `customerId`, `productCode` e cliente: repetição idempotente,
    responde `200 OK` com o recurso existente;
  - qualquer diferença: `409 Conflict`, sem revelar dados do registro
    existente.
- A consulta prévia continua existindo apenas como atalho para o caso comum de
  repetição sequencial, evitando uma exceção desnecessária.

## Consequências

- Duas requisições idênticas concorrentes resultam em um `201` e um `200`, com
  um único registro.
- O comportamento é coberto por teste concorrente em PostgreSQL real
  (Testcontainers) e por testes de integração em H2.
