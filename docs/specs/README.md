# Especificações por fase

| Fase | Cards | Entrega |
|---|---|---|
| [1 — Fundação](fase-1-fundacao.md) | 1.1 Projeto Maven · 1.2 Configuração · 1.3 Domínio | Aplicação inicializável e domínio testado |
| [2 — Persistência e API](fase-2-persistencia-e-api.md) | 2.1 Migração · 2.2 Repositório · 2.3 Criação idempotente · 2.4 Endpoints | `POST`/`GET` com unicidade garantida |
| [3 — Integração e processamento](fase-3-integracao-e-processamento.md) | 3.1 Cliente HTTP · 3.2 Tentativas · 3.3 Worker | Pedidos processados com retry e reconciliação |
| [4 — Erros, segurança e testes](fase-4-erros-seguranca-testes.md) | 4.1 Problem Details · 4.2 JWT · 4.3 Testes | Erros seguros, escopos, cobertura |
| [5 — Entrega](fase-5-entrega.md) | 5.1 Dockerfile · 5.2 Compose · 5.3 CI · 5.4 README | Execução em um comando e documentação final |

Cada card informa dependências, arquivos, passos, critérios de aceite,
cenários negativos e forma de validação.
