# Plano de implementação

Este plano organiza o trabalho futuro para cumprir o enunciado em uma solução
pequena. A stack abaixo é uma proposta de trabalho, ainda não aprovada como
decisão final: Java 21, Spring Boot 3.x, Maven, PostgreSQL e Spring Data JPA.
Não são especificadas versões exatas de dependências.

## Sequência proposta

1. **Inicializar a aplicação.** Criar o projeto Maven com Java 21 e Spring
   Boot 3.x, configuração externa e estrutura simples por responsabilidade.
   Manter uma única aplicação/serviço; não criar microsserviços adicionais.
2. **Modelar e persistir solicitações.** Definir entidade, DTOs de entrada e
   saída e estados `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`. Persistir
   com JPA em PostgreSQL; adicionar migração versionada se escolhida.
3. **Garantir unicidade.** Reforçar a unicidade de `externalId` no banco e
   tratar atomicamente a colisão. Definir respostas para retry com mesmo
   conteúdo, conteúdo divergente e disputa concorrente.
4. **Implementar os endpoints e erros.** Criar `POST /api/v1/requests` e
   `GET /api/v1/requests/{id}`, validações, status HTTP e respostas seguras.
   Adicionar listagem paginada apenas se o tempo permitir.
5. **Implementar a integração.** Isolar a chamada a `POST
   /provider/v1/activate` atrás de um cliente substituível por mock. Definir
   timeouts configuráveis, mapeamento de 2xx/400/500 e timeout, limite de
   tentativas e comportamento após falhas.
6. **Proteger efeitos de retry.** Enviar uma chave de idempotência estável ao
   provedor, caso ele a aceite. Documentar que timeout é ambíguo e que sem
   suporte do provedor não se garante exatamente um efeito externo. Evitar
   manter transação de banco aberta durante chamada remota; descrever como o
   envio seria recuperado se o processo caísse após persistir o pedido.
7. **Adicionar segurança.** No mínimo, documentar OAuth 2.0, validação JWT,
   escopos, chaves e segredo do provedor no README. Implementar Resource
   Server e testes de `401`/`403` somente se houver tempo.
8. **Cobrir os requisitos de teste.** Testar criação válida/inválida,
   duplicidade, consultas existente/inexistente e integração com sucesso e
   erro. Tratar concorrência real com Testcontainers e timeout como
   diferenciais, caso o tempo permita.
9. **Preparar execução.** Criar `Dockerfile` obrigatório. Considerar Compose
   com aplicação, PostgreSQL e mock do provedor, além de CI simples como
   diferenciais.
10. **Completar README e analisar produção.** Adicionar instruções de clone,
    build, execução e teste; decisões de duplicidade, integração e segurança;
    análise do cenário de lentidão do enunciado; como executar em produção;
    pontos não implementados, melhorias futuras e uso de IA.
11. **Revisar entrega.** Confirmar os requisitos obrigatórios de
    [requisitos.md](requisitos.md), executar o build/testes configurados e
    registrar claramente os diferenciais que ficaram fora do escopo.

## Critério de encerramento

A implementação futura está pronta para revisão quando os requisitos
obrigatórios de [requisitos.md](requisitos.md) estiverem atendidos, o comando
de build e testes funcionar em um clone limpo e o README registrar as decisões
tomadas e as limitações restantes. Diferenciais devem ser apresentados como
opcionais, sem alegar que foram entregues quando não foram implementados.
