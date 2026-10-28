# Serviço de ativação de produtos

API Java 21 / Spring Boot 3.5 que registra solicitações de ativação de
produtos, permite consultar seu estado e solicita a ativação a um provedor
externo, com proteção contra duplicidade concorrente.

- [Arquitetura, dependências, modelagem, design da API e convenções](docs/arquitetura.md)
- [Especificações por fase e cards](docs/specs/README.md)
- [Decisões arquiteturais (ADRs)](docs/adr)
- [Requisitos do desafio](docs/requisitos.md)

## Como executar

Pré-requisitos: Docker (para Compose e para o teste com PostgreSQL real) e,
para rodar sem Docker, Java 21. O Maven não precisa estar instalado (`mvnw`).

### Tudo com Docker Compose

```bash
git clone https://github.com/mathfrancisco/desafio-java-ativacao-produtos.git
cd desafio-java-ativacao-produtos
docker compose up --build
```

Sobe PostgreSQL 17, o mock do provedor (WireMock, porta 8089) e a aplicação
(porta 8080). O mock responde conforme o `productCode`:

| `productCode` | Resposta do mock | Estado final esperado |
|---|---|---|
| `FAIL-*` | 400 | `FAILED` na primeira tentativa |
| `UNSTABLE-*` | 503 | `PENDING` com novas tentativas; `FAILED` após 5 |
| `SLOW-*` | 200 após 10 s (acima do timeout de 5 s) | tentativas por timeout; `FAILED` após 5 |
| demais | 200 | `COMPLETED` |

### Chamando a API

Gere um token de desenvolvimento (HS256, válido por 1 h) e use-o:

```bash
TOKEN=$(sh scripts/dev-token.sh local-client "activation:read activation:write")

curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

### Testes

```bash
./mvnw verify
```

Um único comando compila e executa todos os testes. O teste de concorrência
em PostgreSQL real (Testcontainers) é ignorado automaticamente se o Docker não
estiver disponível; os demais usam H2 em modo PostgreSQL e WireMock.

| Requisito | Testes |
|---|---|
| Criação válida e inválida | `ActivationRequestApiTest` (`201` + `Location`, `400` por campo, JSON malformado, corpo grande) |
| Duplicidade | `ActivationRequestApiTest` (repetição idêntica `200`, divergente `409`); `ConcurrentDuplicatePostgresTest` (16 threads em PostgreSQL real) |
| Consulta existente e inexistente | `ActivationRequestApiTest` (`200`, `404`, outro cliente `404`, id inválido `400`) |
| Integração com sucesso e erro | `ActivationProcessorIntegrationTest` (2xx, 400, 500, 503 → sucesso, timeout, esgotamento, reconciliação) |
| Segurança | `ActivationRequestApiTest` (`401`, `403`, isolamento entre clientes) |
| Domínio e arquitetura | `ActivationRequestTest`, `RetryPolicyTest`, `ArchitectureTest` |

### Sem Docker

Com um PostgreSQL disponível, informe `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
e `PROVIDER_BASE_URL` e execute `./mvnw spring-boot:run`.

## Decisões técnicas

### Processamento assíncrono com fila no banco

O `POST` só persiste o pedido como `PENDING` e responde. Um worker agendado
na própria aplicação busca pedidos vencidos, reserva cada um com lock otimista
e chama o provedor **fora de transação**. Se o processo cair após persistir o
pedido, ele continua `PENDING` (ou `PROCESSING` com reserva expirada) e é
retomado. Detalhes em [ADR 0001](docs/adr/0001-fila-persistida-no-banco.md).

### Duplicidade de `externalId`

A garantia é a restrição `UNIQUE` em `external_id`; a consulta prévia é só um
atalho. Em colisão, o serviço relê o registro vencedor e compara:

- mesmo `customerId`, `productCode` e cliente: `200 OK` com o registro
  existente (repetição idempotente — o consumidor pode repetir após timeout);
- conteúdo ou cliente diferente: `409 Conflict`;
- duas requisições idênticas concorrentes: um `201` e um `200`, um único
  registro.

Detalhes em [ADR 0002](docs/adr/0002-duplicidade-externalid.md).

### Integração com o provedor

| Resultado | Ação |
|---|---|
| 2xx | `COMPLETED` |
| 4xx (exceto 408/429) | `FAILED`, sem nova tentativa |
| 5xx, 408, 429 | nova tentativa |
| timeout / falha de rede | nova tentativa com a mesma `Idempotency-Key` |

- Timeouts configuráveis: conexão 2 s, leitura 5 s.
- Até 5 tentativas, backoff exponencial com *jitter* (base 2 s, teto 5 min),
  agendado no banco (`next_attempt_at`), sem prender threads.
- Cada chamada envia `Idempotency-Key: <externalId>`. **Timeout é ambíguo**: o
  provedor pode ter ativado. A nova tentativa só é segura se o provedor
  deduplicar pela chave; sem isso não há garantia de efeito externo exatamente
  uma vez, e o caminho seria consultar o provedor (reconciliação) antes de
  repetir.
- Reconciliação: pedidos em `PROCESSING` com reserva (`locked_until`, 30 s)
  expirada são retomados. O número da tentativa impede que um worker antigo
  sobrescreva o resultado de um mais novo.

Detalhes em [ADR 0003](docs/adr/0003-politica-integracao.md).

### Erros

Todas as respostas de erro seguem Problem Details (RFC 9457) com
`correlationId` e erros por campo. Stack traces, mensagens internas e o corpo
de erro do provedor nunca chegam ao consumidor. O cabeçalho
`X-Correlation-Id` é aceito ou gerado e aparece nos logs.

### Segurança (implementada)

Spring Security Resource Server com JWT:

- **Fluxo:** OAuth 2.0 *Client Credentials*. O sistema consumidor obtém um
  token no servidor de autorização (Keycloak, Entra ID, Cognito etc.) com
  `client_id`/`client_secret` ou `private_key_jwt` e chama a API com
  `Authorization: Bearer`.
- **Validação:** assinatura com as chaves públicas do JWKS do emissor
  (`JWT_ISSUER_URI`, descoberto via OpenID metadata e rotacionado pelo
  emissor), `iss`, `aud` (`JWT_AUDIENCE`), `exp` e `nbf`.
- **Autorização:** `POST` exige `activation:write`; `GET` exige
  `activation:read`. Sem token: `401`; escopo insuficiente: `403`.
- **Isolamento (BOLA):** cada pedido guarda o cliente dono (`azp` ou `sub`).
  Consultas e listagens são filtradas por ele; recurso de outro cliente
  responde `404`, sem revelar existência.
- **Limites:** corpo até 16 KB (`413`), campos até 64 caracteres. Limite de
  taxa fica no API Gateway (ver produção).
- **Credencial do provedor:** não há credencial no código. Em produção ela
  viria de um cofre (AWS Secrets Manager, Azure Key Vault, Vault) injetada por
  variável de ambiente ou montagem de arquivo, com rotação suportando duas
  credenciais válidas durante a troca.
- **Desenvolvimento:** sem `JWT_ISSUER_URI`, a aplicação aceita tokens HS256
  assinados com `JWT_DEV_SECRET` (`scripts/dev-token.sh`). Esse modo não deve
  ser usado em produção.

## Análise do cenário de produção

Sintomas: API de 300 ms para 2,5 s, banco de 50 ms para 1,2 s, provedor de
400 ms para 3 s, CPU 45% e memória 60%.

**O que investigar primeiro.** CPU e memória moderadas indicam que a
aplicação está **esperando**, não calculando. Três componentes ficaram lentos
ao mesmo tempo, então antes de otimizar cada um procuro o que eles têm em
comum: o que mudou (deploy, configuração, volume, migração, job em lote), e
recursos compartilhados — pool de conexões, rede/DNS, nó ou zona de cloud,
dependência comum.

**Como localizar onde está a lentidão.**

1. Rastreamento distribuído (OpenTelemetry): decompor a latência de uma
   requisição em spans de HTTP, espera por conexão do pool, query e chamada
   ao provedor. Se a maior parte do tempo está *antes* da query, o gargalo é
   o pool, não o banco.
2. Métricas do pool Hikari: `hikaricp.connections.pending`,
   `hikaricp.connections.acquire`, `active` x `max`. Pool esgotado com CPU
   baixa é o padrão mais comum para esse quadro.
3. Banco: `pg_stat_statements` (queries mais lentas e mais frequentes),
   `pg_stat_activity` (sessões esperando lock, `idle in transaction`), plano
   de execução das queries afetadas, I/O e locks.
4. Provedor: latência medida do nosso lado (`http.client.requests` por
   status) comparada à latência informada pelo provedor; erros e timeouts.
5. Threads: *thread dump* da JVM — muitas threads em `getConnection` ou em
   socket read apontam a espera.

**Hipótese de causa comum.** Uma hipótese que explica os três sintomas:
**transações mantidas abertas durante a chamada ao provedor**. Quando o
provedor desacelera, cada requisição segura uma conexão do banco por mais
tempo; o pool esgota; as queries passam a incluir tempo de espera por conexão
(o "banco lento" de 1,2 s é parcialmente fila no pool); e as requisições
acumuladas aumentam a concorrência sobre o provedor, que piora. Outras
hipóteses a descartar: query sem índice após crescimento da tabela,
contenção de locks, latência de rede da zona, *noisy neighbor* no banco.

**Como confirmar.** Se a hipótese for verdadeira, os traces mostrarão a
conexão adquirida antes da chamada HTTP e liberada depois;
`hikaricp.connections.pending` > 0 coincidirá com os picos; o tempo de
execução real no `pg_stat_statements` ficará próximo do normal (50 ms)
enquanto o tempo medido pela aplicação sobe; e `pg_stat_activity` mostrará
sessões `idle in transaction`.

**Melhorias.** Não manter transação durante chamada remota (esta solução já
separa as duas); processamento assíncrono do provedor (já adotado); timeouts
curtos e explícitos; limitar concorrência ao provedor (bulkhead) e circuit
breaker; índices para as queries lentas; dimensionar o pool pelo banco, não
pela demanda.

**Como validar a correção após publicar.** Publicar em canário, comparar p50,
p95 e p99 da API, `hikaricp.connections.pending` e a latência das queries com
a versão anterior sob o mesmo tráfego; acompanhar taxa de erros e a fila de
pedidos `PENDING`; só então ampliar o rollout. Um teste de carga com o
provedor simulado lento (o mock `SLOW-*`) reproduz o cenário antes da
publicação.

## Execução em produção

- **Execução:** imagem Docker em estágios, JRE apenas, usuário não-root,
  `MaxRAMPercentage=75`, *graceful shutdown*. Em Kubernetes (ou ECS), com
  probes em `/actuator/health/liveness` e `/actuator/health/readiness`, e
  `requests/limits` de CPU e memória.
- **Várias instâncias:** suportado. A unicidade é garantida pelo banco e a
  reserva de pedidos por lock otimista; instâncias não precisam se
  coordenar.
- **Configuração e segredos:** apenas variáveis de ambiente
  (`DB_*`, `PROVIDER_*`, `JWT_*`), com segredos vindos de cofre.
- **Banco:** PostgreSQL gerenciado (RDS, Cloud SQL, Azure), migrações Flyway
  na inicialização (ou em job separado no pipeline), backups e réplica.
- **Borda:** API Gateway / Ingress com TLS, limite de taxa por cliente e
  tamanho de requisição.
- **Observabilidade:** métricas em `/actuator/prometheus` (HTTP, JVM, pool,
  cliente HTTP); logs com `correlationId` e `requestId`, sem segredos; alertas
  para pedidos `PENDING` antigos, taxa de `FAILED` e latência do provedor.
- **CI/CD:** o workflow `.github/workflows/ci.yml` executa `./mvnw verify` e
  constrói a imagem; a evolução é publicar a imagem e fazer deploy por
  ambiente com canário.

## Pontos não implementados e melhorias futuras

- Circuit breaker e bulkhead para o provedor (Resilience4j).
- Reconciliação por consulta ao provedor em caso de timeout, se ele oferecer
  endpoint de status.
- Publicação de eventos de mudança de estado (outbox para Kafka/SNS) para
  notificar consumidores em vez de exigir *polling*.
- `FOR UPDATE SKIP LOCKED` ou broker quando o volume tornar a varredura de
  pendentes um gargalo.
- Logs JSON e exportação de traces OpenTelemetry (hoje: logs em texto com
  correlação e métricas Prometheus).
- Limite de taxa na aplicação (hoje delegado ao gateway).
- O limite de corpo usa `Content-Length`; requisições *chunked* dependem do
  limite do gateway.
- Testes de carga automatizados.
