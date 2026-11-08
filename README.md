# algashop-billing-scheduler

Job que cancela faturas vencidas do [`algashop-billing`](https://github.com/gabriel-lima258/algashop-billing).

O menor dos quatro serviços — **8 arquivos Java** — e o de desenho mais incomum: ele não é um servidor. Sobe, faz uma coisa, e morre.

---

## O problema

Uma fatura nasce com prazo: `expiresAt = agora + 3 dias`. Passado o prazo sem pagamento, ela precisa ser cancelada, e o pagamento correspondente precisa ser cancelado **no gateway** — senão sobra uma autorização pendurada.

Isso não é trabalho de requisição. Ninguém chama uma API para pedir "cancele o que venceu"; é uma varredura periódica sobre um conjunto que pode ser grande. E é exatamente o tipo de tarefa que não combina com um serviço HTTP: um roda o tempo todo esperando alguém chegar, o outro roda de vez em quando e pode ser pesado.

Daí um serviço separado do `billing`, com perfil de escala próprio.

---

## Stack

| | |
|---|---|
| **Java** | 25 |
| **Spring Boot** | 4.0.1 |
| **Banco** | PostgreSQL — o banco `billing` |
| **Porta** | **nenhuma** |
| **Pacote raiz** | `com.algaworks.algashop.billingscheduler` |
| **Acesso a dados** | JDBC puro (`JdbcOperations`), sem JPA |

---

## Três decisões que definem o serviço

### 1. Não sobe servidor HTTP — de propósito

```gradle
implementation('org.springframework.boot:spring-boot-starter-webmvc') {
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-tomcat'
}
```

O starter web entra pelo que ele traz de utilitário, mas o **Tomcat é excluído**. Sem servidor embutido, a aplicação não fica escutando nada — e o Spring Boot encerra o processo assim que a tarefa termina.

É o que torna o serviço *short-lived*: ele tem começo, meio e fim, como um script, com todo o ferramental do Spring disponível.

### 2. `ApplicationRunner`, não `@Scheduled`

```java
@Component
public class CancelExpiredInvoicesScheduler implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        applicationService.cancelExpiredInvoices();
    }
}
```

A periodicidade **não está no código**. Não há cron, não há `fixedRate` — quem decide de quanto em quanto tempo isso roda é um agendador externo, tipicamente um CronJob do Kubernetes.

A troca é deliberada. Com `@Scheduled`, o processo precisa ficar vivo entre execuções, consumindo memória para não fazer nada, e duas instâncias significam trabalho duplicado. Com CronJob, o orquestrador controla a periodicidade, a concorrência e o retry — e o serviço só precisa saber fazer o trabalho uma vez.

### 3. Fala com o banco do `billing` diretamente

Este é o ponto que precisa estar claro para quem lê o código:

```sql
select i.id, ps.gateway_code
from invoice i
inner join payment_settings ps on i.payment_settings_id = ps.id
where i.expires_at <= now() - interval '1 days'
  and i.status = ?
order by i.expires_at asc
limit ?
for update
skip locked
```

O scheduler **lê e escreve nas tabelas do `billing`** por SQL, sem passar pela API daquele serviço. A única chamada HTTP que ele faz é para o gateway.

É um atalho assumido, e o custo tem nome: ele contorna `Invoice.cancel()`, não dispara `InvoiceCanceledEvent`, e o `UPDATE` não incrementa a coluna `version` do lock otimista. Além disso, o schema do `billing` vira contrato implícito — mudar uma coluna lá quebra este serviço sem aviso.

O que se ganha em troca é simplicidade: uma varredura em lote resolvida por uma consulta, em vez de N chamadas HTTP.

#### `FOR UPDATE SKIP LOCKED` — o detalhe bonito

Duas instâncias do job podem rodar ao mesmo tempo, e é o `SKIP LOCKED` que faz isso ser seguro sem coordenação nenhuma.

`FOR UPDATE` bloqueia as linhas selecionadas. `SKIP LOCKED` diz ao Postgres: *"as que já estiverem bloqueadas por outra transação, pule — me dê as próximas"*. O resultado é que cada instância pega um lote **disjunto**, sem esperar e sem processar a mesma fatura duas vezes.

Sem `SKIP LOCKED`, a segunda instância ficaria bloqueada esperando a primeira; sem `FOR UPDATE`, as duas processariam as mesmas faturas.

---

## O fluxo, de ponta a ponta

1. Abre uma transação (`TransactionTemplate`)
2. Seleciona até **50** faturas `UNPAID` vencidas há mais de **1 dia**, com `FOR UPDATE SKIP LOCKED`
3. Se não houver nenhuma, loga e encerra sem escrever
4. Para cada uma, chama `PUT /api/v1/payments/{paymentId}/cancel` no **FastPay**
5. As que falharem no gateway são **filtradas fora** — não são atualizadas, e ficam para a próxima execução
6. As que passaram vão num `batchUpdate`: `status = 'CANCELED'`, `canceled_at = now()`, `cancel_reason = 'Invoice expired'`

O passo 5 é o que torna o job seguro para repetir: nada é marcado como cancelado no banco sem que o gateway tenha confirmado o cancelamento antes.

---

## Como rodar

Precisa de duas coisas de pé, ambas do repositório [`algashop-meta`](https://github.com/gabriel-lima258/algashop-meta):

```bash
docker compose -f docker-compose.tools.yml up -d
```

- **PostgreSQL** na `5433`, com o banco `billing` **já migrado** — o schema vem do `algashop-billing`, não daqui
- **FastPay** na `9995`, o gateway simulado

```bash
./gradlew bootRun
```

A aplicação sobe, executa a varredura, loga o resultado e **encerra**. Se ela ficar rodando, algo trouxe o Tomcat de volta ao classpath.

Para ver o job fazendo trabalho de verdade, suba o `algashop-billing` antes: ele carrega massa com 6 faturas `UNPAID`.

---

## Testes

```bash
./gradlew test
./gradlew integrationTest
```

As tasks existem e seguem o mesmo padrão dos outros serviços — mas **não há testes escritos** neste repositório.

---

## Imagem Docker

```bash
./gradlew bootJar
docker build -t algashop/billing-scheduler:dev .
```

Ou multi-arquitetura com push:

```bash
./gradlew dockerBuild
```

A base é `eclipse-temurin:25-jre`, acompanhando o toolchain do `build.gradle`. Sendo um job efêmero, esta é a imagem que iria num `CronJob` do Kubernetes — o `restartPolicy` e o `schedule` ficam do lado do orquestrador.

---

## Documentação

Caderno de estudos do projeto: [`algashop-docs`](https://github.com/gabriel-lima258/algashop-docs).

- [Jobs agendados](https://github.com/gabriel-lima258/algashop-docs/blob/main/04-infraestrutura/04-scheduled-jobs.md) — `@Scheduled`, execução distribuída e controle de concorrência
- [Arquitetura](https://github.com/gabriel-lima258/algashop-docs/blob/main/00-visao-geral/01-arquitetura.md) — onde este serviço se encaixa, e a exceção que ele abre à regra de banco por serviço
- [Ambiente local](https://github.com/gabriel-lima258/algashop-docs/blob/main/04-infraestrutura/01-ambiente-local.md) — portas, bancos e problemas comuns
- [Docker](https://github.com/gabriel-lima258/algashop-docs/blob/main/04-infraestrutura/02-docker.md) — build de imagem e multi-arquitetura
