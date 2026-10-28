# Requisitos do desafio

## Contexto e prazo

Construir uma versão simplificada de um serviço que recebe solicitações de
ativação de produtos de clientes, registra os pedidos, permite consultar
seu estado e solicita a ativação a um provedor externo. O desafio favorece
uma solução simples e bem estruturada.

O prazo é de até três dias corridos após o recebimento. A data de recebimento
não foi informada, então não é possível determinar uma data limite. O tempo
recomendado é de até quatro horas.

## Requisitos obrigatórios

### Tecnologias

- Java 17 ou Java 21.
- Spring Boot para a aplicação; Spring MVC para a API REST e Spring Data JPA
  com Hibernate para persistência.
- Maven ou Gradle; Maven é usado pela vaga.
- Banco de dados relacional, como PostgreSQL ou SQL Server. H2 pode ser usado
  para testes locais.

### API e domínio

- `POST /api/v1/requests` cria uma solicitação e valida os dados obrigatórios.
  Exemplo de entrada:

  ```json
  {
    "externalId": "EXT-12345",
    "customerId": "CUST-10001",
    "productCode": "INTERNET-500"
  }
  ```

- A solicitação contém, no mínimo, `id`, `externalId`, `customerId`,
  `productCode`, `status`, `createdAt` e `updatedAt`.
- `GET /api/v1/requests/{id}` devolve a solicitação e seu estado atual, ou
  uma resposta HTTP apropriada se ela não existir.
- Os estados esperados são `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`.
- O README documenta a abordagem contra duplicidade de `externalId`, inclusive
  para solicitações concorrentes.
- A aplicação integra-se ao endpoint `POST /provider/v1/activate`. Um mock,
  stub ou servidor de testes pode representar o provedor.
- O comportamento distingue sucesso, erro de cliente, erro do provedor e
  timeout. O enunciado requer definir quais resultados concluem, falham ou
  permitem tentar novamente; as decisões ainda estão pendentes.
- A API trata dados inválidos, registro inexistente, duplicidade, erro de
  integração e erro inesperado. Não envia stack traces nem detalhes internos
  ao consumidor.
- A solução implementa a proteção dos endpoints com Spring Security, JWT e
  OAuth 2.0, ou o README descreve como a protegeria.
- Existe um `Dockerfile` para executar a aplicação.
- O código atende aos critérios de qualidade avaliados: legibilidade,
  separação de responsabilidades, tratamento de exceções, nomenclatura,
  boas práticas com Spring, SOLID e Clean Code, sem padrões desnecessários.

### Testes obrigatórios

- Criação válida e rejeição de dados inválidos.
- Duplicidade: dois pedidos com o mesmo `externalId` não criam dois registros
  válidos.
- Consulta de solicitação existente e inexistente.
- Integração com sucesso e erro do provedor externo.

### README de entrega

O README final inclui instruções para executar a aplicação e os testes a
partir de um clone limpo, decisões técnicas, análise do cenário de produção,
execução em produção, pontos pendentes e melhorias futuras. As decisões
abrangem duplicidade e comportamento da integração. Se a segurança for
descrita em vez de implementada, o README explica a abordagem.

## Diferenciais opcionais

- Spring Boot 3.x, recursos modernos da linguagem e um único comando para
  build e testes.
- Migrações de schema versionadas, por exemplo com Flyway ou Liquibase.
- `docker-compose.yml` para aplicação, banco e mock do provedor; pipeline de
  CI; e registros de decisão arquitetural.
- `POST` retorna `201 Created` e `Location`; documenta formatos e limites de
  validação; usa datas UTC em ISO-8601; mantém DTOs separados das entidades.
- `GET /api/v1/requests?status=&customerId=&page=&size=` oferece paginação e
  filtros com índices justificados no README.
- Transições de estado controladas pelo domínio, concorrência protegida com
  lock otimista e política documentada para falhas temporárias.
- Semântica de repetição explícita: mesmo identificador e mesmo conteúdo;
  identificador repetido com conteúdo divergente; duas requisições idênticas
  concorrentes.
- Timeouts configuráveis de conexão e leitura, chave de idempotência do
  provedor, limite de tentativas e política de backoff.
- Circuit breaker, backoff exponencial com jitter e reconciliação de pedidos
  presos em `PROCESSING`, se forem úteis.
- Problem Details (RFC 9457), identificador de correlação e erros de
  validação por campo.
- Spring Security Resource Server com escopos separados de leitura e escrita,
  testes de `401` e `403`, isolamento entre consumidores contra BOLA e limites
  de tamanho e taxa.
- Teste concorrente de duplicidade contra banco real com Testcontainers,
  teste de timeout, teste unitário de transições de domínio e testes de
  arquitetura com ArchUnit.
- Actuator com liveness e readiness, logs correlacionados sem segredos,
  métricas Micrometer/Prometheus, logs JSON e tracing OpenTelemetry.
- Imagem Docker enxuta, build em estágios, usuário não-root, configuração por
  ambiente, memória da JVM adequada ao contêiner, health check e desligamento
  gracioso.
- Descrição da execução como microsserviço em cloud, gestão de segredos,
  concorrência entre instâncias, publicação de eventos e CI/CD.
- Histórico de commits pequenos e explicação do uso de assistentes de IA.

## Decisões ainda necessárias

Não se deve presumir que consultar a existência do `externalId` antes de
inserir evita concorrência: a garantia precisa incluir uma restrição única no
banco e tratamento da colisão. A resposta a repetições idênticas ou
divergentes ainda precisa ser escolhida.

Também precisam ser definidas as ações para respostas 2xx, 400, 500 e timeout
do provedor, bem como as tentativas para 429/503, se cobertas. Timeout tem
resultado ambíguo: o provedor pode ter concluído a ativação. A integração deve
considerar uma chave de idempotência estável, se o provedor a suportar.
Sem idempotência ou reconciliação fornecida pelo provedor, não há garantia de
efeito externo exatamente uma vez.

## Cenário obrigatório de análise de produção

Responder no README final, sem presumir uma causa antes de investigar:

- O que investigar primeiro quando a média da API sobe de 300 ms para 2,5 s,
  consultas no banco sobem de 50 ms para 1,2 s, o provedor externo sobe de
  400 ms para 3 s, com CPU a 45% e memória a 60%?
- Como localizar se a lentidão está na aplicação, no banco ou na integração?
- Quais logs e métricas ajudariam, que melhorias poderiam ser consideradas
  e como confirmar uma hipótese?

**Diferenciais:** formular e confirmar uma hipótese de causa comum para os
três sintomas; explicar como validar a correção após publicá-la.

## Segurança a descrever ou implementar

Explicar o fluxo OAuth 2.0 para comunicação entre sistemas, a validação de
assinatura, emissor, audiência e expiração do JWT, a origem das chaves,
autorização por escopo e armazenamento e rotação da credencial do provedor.
Não é necessário criar servidor de autenticação.
