# Serviço de ativação de produtos

Repositório de preparação para o desafio técnico Java Developer Pleno/Sênior.
O desafio consiste em registrar solicitações de ativação de produtos de
clientes, consultar seu estado e integrá-las a um provedor externo.

## Estado atual

Este repositório contém documentação de preparação; a aplicação ainda não foi
implementada. Ainda não existem `pom.xml`, `src/`, `Dockerfile`, testes ou
`mvnw`. Portanto, `./mvnw verify` é um comando de aceitação previsto para a
implementação futura e não pode ser executado agora.

## Escopo do desafio

O contrato mínimo inclui `POST /api/v1/requests` para criar uma solicitação e
`GET /api/v1/requests/{id}` para consultá-la. O recurso inclui `id`,
`externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt`.
Os estados esperados são `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`.

Java 17 ou 21, Spring Boot, API REST, persistência relacional, prevenção de
duplicidade por `externalId`, integração com o provedor, tratamento de erros,
orientação de segurança no README, testes dos fluxos obrigatórios e um
`Dockerfile` fazem parte do enunciado obrigatório. Paginação, migrações,
Docker Compose, CI, testes concorrentes com Testcontainers, Resource Server
JWT implementado e observabilidade avançada são diferenciais.

## Documentação

- [Requisitos do desafio](docs/requisitos.md): requisitos obrigatórios,
  diferenciais e cenários que precisam de decisão.
- [Plano de implementação](docs/plano-de-implementacao.md): proposta de
  execução incremental para completar a solução.

## Execução

Ainda não há aplicação executável. As instruções de build, execução local,
testes e execução em produção serão adicionadas durante a implementação.

## Seções pendentes para a entrega

O README final precisa documentar como executar a aplicação e seus testes a
partir de um clone limpo, as decisões técnicas, a estratégia de duplicidade,
o comportamento da integração, a segurança (se descrita em vez de
implementada), a análise do cenário de lentidão em produção, a execução em
produção, os pontos não implementados e melhorias futuras. Como diferencial,
o enunciado recomenda informar o uso de IA; Codex foi usado para analisar o
HTML do desafio e preparar esta documentação. A implementação da aplicação
continua pendente.

As respostas de segurança, operação, produção e análise continuam pendentes;
nenhuma decisão ou validação de implementação é afirmada aqui. O prazo do
desafio é de três dias corridos após o recebimento; a data de recebimento não
foi informada. O tempo recomendado é de até quatro horas.
