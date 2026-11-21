# Order-service

Microsserviço de **pedidos** da loja online do grupo `pato-nego`. Ele registra o pedido de um usuário com os produtos e as quantidades escolhidas, congela o preço de cada produto no momento da compra, lista os pedidos de cada usuário e mostra o total em outra moeda quando pedido. Os dados ficam no PostgreSQL (AWS RDS).

Esta é a **implementação**. A interface pública (rotas e DTOs) fica no repositório [Order](https://github.com/pato-nego/Order), que este projeto usa como dependência.

## Lugar na arquitetura

```
cliente ──► gateway ──► order ──► PostgreSQL (RDS, schema "orders")
          valida o JWT   │
          e envia        ├──► product   preço de cada produto
          "id-account"   └──► exchange  cotação da moeda
```

- O cliente **nunca** chama o Order diretamente. O gateway valida o cookie `__store_jwt_token` e repassa o usuário no header `id-account`. As rotas `/orders` e `/orders/**` precisam estar configuradas no gateway.
- O Order **confia** nesse header. Por isso a porta do serviço não pode ficar aberta para a internet, só para o gateway.
- Por dentro da rede privada, o Order consulta o **Product** (`GET /products/{id}`) e o **Exchange** (`GET /exchanges/USD/{moeda}`).

## Contrato e implementação

| Repositório | O que tem |
|---|---|
| [`Order`](https://github.com/pato-nego/Order) | a interface Feign `OrderController` e os DTOs de entrada e saída, com a validação |
| `Order-service` (este) | o controlador que implementa a interface, as regras, o banco e os clientes do Product e do Exchange |

Para compilar este projeto, o contrato precisa estar instalado no Maven local (veja [Rodar localmente](#rodar-localmente)).

## Endpoints

Todas as rotas de pedido exigem o header `id-account`. Sem ele a resposta é `401`.

| Método | Rota | Corpo | Resposta |
|---|---|---|---|
| `POST` | `/orders` | `{ "items": [{ "idProduct", "quantity" }] }` | `201 Created` com o pedido e o header `Location` |
| `GET` | `/orders` | — | `200 OK` com o resumo dos pedidos do usuário, do mais antigo ao mais novo |
| `GET` | `/orders/{id}` | — | `200 OK` com o pedido, ou `404` |
| `GET` | `/orders/{id}?currency=BRL` | — | igual, com os valores convertidos, ou `422` |
| `GET` | `/orders/health-check` | — | `200 OK` sem corpo, enquanto o serviço está de pé |

O `id` do pedido e o de cada item são **UUID v7**, gerados pelo serviço. O health-check não pede `id-account`, não devolve nada interno (nunca variáveis de ambiente) e serve para as *probes* do orquestrador.

### Exemplos

Os exemplos usam a porta `8090` do [modo local](#rodar-localmente). Em produção quem envia `id-account` é o gateway.

Criar um pedido:

```bash
curl -i -X POST http://localhost:8090/orders \
  -H "id-account: 3b1f6d2a-8c4e-4f7a-9b21-6e5d0c8a7f13" \
  -H "Content-Type: application/json" \
  -d '{"items": [
        {"idProduct": "0195abfb-7074-73a9-9d26-b4b9fbaab0a8", "quantity": 2},
        {"idProduct": "0195abfe-e416-7052-be3b-27cdaf12a984", "quantity": 10}
      ]}'
```

```
HTTP/1.1 201
Location: http://localhost:8090/orders/01a1fb53-320f-7521-96f0-0cebfd2bf19c
Content-Type: application/json
```

```json
{
  "id": "01a1fb53-320f-7521-96f0-0cebfd2bf19c",
  "date": "2026-11-20T23:38:15",
  "currency": "USD",
  "items": [
    {
      "id": "01a1fb53-322a-713b-9791-61f6ba199cf8",
      "product": { "id": "0195abfb-7074-73a9-9d26-b4b9fbaab0a8" },
      "quantity": 2,
      "total": 20.24
    },
    {
      "id": "01a1fb53-322a-7acf-b19a-d327209929ff",
      "product": { "id": "0195abfe-e416-7052-be3b-27cdaf12a984" },
      "quantity": 10,
      "total": 6.20
    }
  ],
  "total": 26.44
}
```

Listar os pedidos do usuário (só o resumo):

```bash
curl http://localhost:8090/orders -H "id-account: 3b1f6d2a-8c4e-4f7a-9b21-6e5d0c8a7f13"
```

```json
[
  { "id": "01a1fb53-320f-7521-96f0-0cebfd2bf19c", "date": "2026-11-20T23:38:15", "total": 26.44 }
]
```

Ver o pedido em reais, com a cotação de exemplo `6.00`:

```bash
curl "http://localhost:8090/orders/01a1fb53-320f-7521-96f0-0cebfd2bf19c?currency=BRL" \
  -H "id-account: 3b1f6d2a-8c4e-4f7a-9b21-6e5d0c8a7f13"
```

```json
{
  "id": "01a1fb53-320f-7521-96f0-0cebfd2bf19c",
  "date": "2026-11-20T23:38:15",
  "currency": "BRL",
  "items": [
    { "id": "01a1fb53-322a-713b-9791-61f6ba199cf8", "product": { "id": "0195abfb-7074-73a9-9d26-b4b9fbaab0a8" }, "quantity": 2, "total": 121.44 },
    { "id": "01a1fb53-322a-7acf-b19a-d327209929ff", "product": { "id": "0195abfe-e416-7052-be3b-27cdaf12a984" }, "quantity": 10, "total": 37.20 }
  ],
  "total": 158.64
}
```

### Validação

| Campo | Regra |
|---|---|
| `items` | obrigatório, com pelo menos um item |
| `items[].idProduct` | obrigatório, um UUID |
| `items[].quantity` | obrigatório, inteiro maior que zero |
| `currency` (parâmetro) | opcional, três letras (`BRL`, `eur`...), sem diferenciar maiúsculas |

### Como os valores são calculados

- **Preço congelado:** na criação, o preço de cada produto vem do Product e é guardado no item (`unit_price`). Se o preço mudar depois, os pedidos antigos não mudam.
- **Moeda base:** tudo é guardado em **USD**, com duas casas decimais e arredondamento `HALF_UP`.
- **Totais:** o total do item é `preço × quantidade`, e o total do pedido é a soma dos itens.
- **Conversão:** com `?currency=`, cada item é convertido pela cotação de **venda** (`sell`) do Exchange e arredondado, e o total é a soma dos itens já convertidos. Assim os itens sempre somam o total mostrado. A conversão não é gravada: o pedido continua em USD.
- **USD ou sem `currency`:** não chama o Exchange.
- **Produto repetido no mesmo pedido:** é consultado uma vez só.
- **Data:** é a de criação, sem fuso horário, em segundos. O container roda em UTC.

### Erros

Todos seguem o formato padrão do Spring: `timestamp`, `status`, `error`, `message` e `path`.

| Status | Quando |
|---|---|
| `400` | corpo inválido, `id` que não é UUID, ou produto que não existe no Product |
| `401` | falta o header `id-account` |
| `404` | pedido inexistente **ou de outro usuário** (não revela que existe) |
| `422` | moeda com formato inválido ou que o Exchange não conhece |
| `503` | Product ou Exchange fora do ar, ou com resposta inválida |

Exemplos reais:

```json
{ "status": 400, "error": "Bad Request", "message": "Product not found: 00000000-0000-4000-8000-000000000000", "path": "/orders" }
```

```json
{ "status": 400, "error": "Bad Request", "message": "Validation failed for object='orderIn'. Error count: 1", "path": "/orders" }
```

```json
{ "status": 404, "error": "Not Found", "message": "Order not found", "path": "/orders/00000000-0000-4000-8000-000000000001" }
```

```json
{ "status": 422, "error": "Unprocessable Content", "message": "Unsupported currency", "path": "/orders/01a1fb53-320f-7521-96f0-0cebfd2bf19c" }
```

```json
{ "status": 503, "error": "Service Unavailable", "message": "Product service unavailable", "path": "/orders" }
```

(Nesses exemplos o `timestamp` foi omitido para encurtar.) Por causa do [cache](#desempenho-e-cache), se o Product cair, os pedidos com produtos consultados nos últimos 30 segundos continuam funcionando, e os demais respondem `503`.

## Modelo de dados

O banco usa o schema **`orders`**, criado e versionado pelo Flyway em [`V2026.11.18.001__create_tables.sql`](src/main/resources/db/migration/V2026.11.18.001__create_tables.sql). O Hibernate não altera as tabelas.

`orders.orders`:

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | `uuid` | chave primária, UUID v7 |
| `id_account` | `varchar(36)` | não nulo, o usuário dono do pedido, com índice |
| `created_at` | `timestamp` | não nulo |
| `total` | `numeric(19,2)` | não nulo, em USD |

`orders.order_items`:

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | `uuid` | chave primária, UUID v7 |
| `id_order` | `uuid` | chave estrangeira para `orders`, com `ON DELETE CASCADE` e índice |
| `id_product` | `uuid` | não nulo, sem chave estrangeira (o produto fica em outro serviço) |
| `quantity` | `integer` | não nulo, maior que zero |
| `unit_price` | `numeric(19,2)` | preço do produto no momento da compra, em USD |
| `total` | `numeric(19,2)` | `unit_price × quantity` |

## Tecnologias

- Java 25 e Spring Boot 4.1
- Spring Web MVC, Spring Data JPA (Hibernate) e Bean Validation
- Spring Cloud OpenFeign para chamar o Product e o Exchange
- PostgreSQL e Flyway
- Caffeine, para o cache em memória
- Lombok
- Maven (é preciso ter o JDK 25 e o Maven instalados)
- Docker, Docker Compose, GitHub Actions e Trivy

## Estrutura do código

```
src/main/java/store/order/
├── OrderServiceApplication.java   ponto de entrada
├── OrderResource.java             rotas HTTP (implementa a interface do contrato)
├── OrderService.java              regras, chamadas ao Product e ao Exchange, cache
├── OrderRepository.java           acesso ao banco (Spring Data JPA)
├── Order.java                     entidade do pedido
├── OrderItem.java                 entidade do item, com o cálculo do total
├── OrderParser.java               entidade para DTO de saída, com a conversão de moeda
├── ProductClient.java             cliente Feign do Product
└── ExchangeClient.java            cliente Feign do Exchange
src/main/resources/
├── application.yaml               configuração
└── db/migration/                  scripts do Flyway
src/test/java/store/order/         testes
```

O fluxo de uma requisição é `OrderResource → OrderService → OrderRepository`. As entidades `Order` e `OrderItem` não saem da API: a saída é sempre um DTO do contrato (`OrderOut`, `OrderSummaryOut`).

## Rodar localmente

Você precisa do **JDK 25**, do **Maven** e do **Docker**, para o PostgreSQL.

1. Instale o contrato (o repositório `Order` deve estar ao lado deste):

```bash
mvn -f ../Order/pom.xml clean install
```

2. Suba um PostgreSQL:

```bash
docker run -d --name order-db -p 5432:5432 \
  -e POSTGRES_DB=order -e POSTGRES_USER=order -e POSTGRES_PASSWORD=order postgres:18
```

3. Rode o serviço, apontando para o banco, o Product e o Exchange:

```bash
export DATABASE_HOST=localhost DATABASE_PORT=5432 DATABASE_DB=order
export DATABASE_USERNAME=order DATABASE_PASSWORD=order
export PRODUCT_URL=http://localhost:8080 EXCHANGE_URL=http://localhost:8000
export SERVER_PORT=8090
mvn spring-boot:run
```

A API sobe em `http://localhost:8090`. O Flyway cria o schema e as tabelas na primeira execução.

O `POST /orders` precisa do Product no ar, e a conversão de moeda precisa do Exchange. Sem eles, o health-check e as consultas em USD funcionam, e o resto responde `503`. Como o gateway não está no caminho, envie o header `id-account` manualmente, como nos exemplos.

## Testes

```bash
mvn test
```

São 23 testes, e nenhum precisa de banco nem de rede:

| Classe | Testes | O que cobre |
|---|---|---|
| `OrderServiceTest` | 16 | regras de cálculo, conversão de moeda, erros, isolamento por usuário e cache (com o Product e o Exchange simulados no Mockito) |
| `OrderTest` | 3 | a entidade: total, itens e arredondamento |
| `OrderInValidationTest` | 4 | a validação do corpo da requisição |

## Configuração

O arquivo [`application.yaml`](src/main/resources/application.yaml) é a configuração de produção. O que varia vem de variáveis de ambiente:

| Variável | Padrão | Descrição |
|---|---|---|
| `DATABASE_HOST` | — | endereço do PostgreSQL |
| `DATABASE_PORT` | — | porta do PostgreSQL (`5432`) |
| `DATABASE_DB` | — | nome do banco |
| `DATABASE_USERNAME` | — | usuário do banco |
| `DATABASE_PASSWORD` | — | senha do usuário |
| `SPRING_DATASOURCE_URL` | montada com as quatro acima | se definida, substitui a URL inteira (usada em produção, para o TLS) |
| `PRODUCT_URL` | `http://product:8080` | onde está o Product |
| `EXCHANGE_URL` | `http://exchange:8000` | onde está o Exchange |

O modelo do arquivo `.env` de produção está em [`.env.example`](.env.example). O `.env` real **nunca** vai para o Git.

Outras definições do `application.yaml`: o Flyway usa o schema `orders`; os clientes Feign têm timeout de **2 s** para conectar e **5 s** para ler; o `open-in-view` do JPA fica desligado; as threads virtuais ficam ligadas; e as mensagens de erro aparecem na resposta.

Em produção a conexão com o banco usa TLS com verificação do certificado (`sslmode=verify-full`). Para isso o arquivo `rds-ca.pem`, o pacote de certificados do RDS da região `sa-east-1`, precisa estar ao lado do `docker-compose.yml`:

```bash
curl -o rds-ca.pem https://truststore.pki.rds.amazonaws.com/sa-east-1/sa-east-1-bundle.pem
```

## Desempenho e cache

Quatro mudanças tiram o Order do limite de CPU em que ele ficava:

| Mudança | Efeito |
|---|---|
| **Cache do preço do produto**, por 30 s | o Product deixa de ser chamado a cada pedido |
| **Cache da cotação**, por 60 s | o Exchange deixa de ser chamado a cada conversão |
| **Listagem sem os itens** | `GET /orders` busca só `id`, `date` e `total`, em vez de carregar todos os itens de todos os pedidos |
| **Threads virtuais** | requisições que esperam o banco ou outro serviço deixam de ocupar uma thread do sistema |

Detalhes do cache: é em memória (Caffeine), **por instância**; guarda no máximo 10 mil produtos e 200 moedas; e **falhas não são guardadas**, então uma resposta de erro do Product ou do Exchange não é reaproveitada. O custo é a defasagem: um pedido pode usar um preço de até 30 s atrás.

Medido com o k6, com 1 CPU e 700 MB (como na EC2), o Product simulado em 20 ms e o Exchange em 10 ms:

| Cenário | Antes | Depois |
|---|---|---|
| `POST /orders` (3 produtos) | 34 a 44 req/s | ~114 req/s |
| `GET /orders` (200 pedidos de 5 itens) | 22 req/s, p95 de 2,0 s | ~264 req/s, p95 de 0,17 s |
| `GET /orders/{id}?currency=BRL` | 64 req/s | ~241 req/s |

As chamadas ao Product caíram de cerca de 3.000 para 20 durante o teste, e as ao Exchange de cerca de 1.900 para 1. Os números absolutos valem só para esse ambiente (o k6 dividia a CPU com o serviço), mas a comparação entre antes e depois vale.

## Segurança

- **Identidade do usuário:** vem só do header `id-account`, definido pelo gateway depois de validar o JWT. O serviço só pode ser acessado pelo gateway.
- **Pedido de outro usuário:** toda consulta filtra por `id` e por `id_account`, e devolve `404`, e não `403`, para não revelar que o pedido existe.
- **Health-check:** não devolve nenhuma informação interna.
- **Segredos:** o `.env` fica só na máquina de produção, com permissão `600`. O CI usa uma chave SSH própria.
- **Dependências e imagem:** o CI varre a imagem com o Trivy antes de cada deploy (veja abaixo).

## Deploy

### Imagem Docker

O [`Dockerfile`](Dockerfile) copia o jar já compilado para uma imagem com o Java 25. Compile antes:

```bash
mvn clean package
docker build -t order-service:latest .
```

### Docker Compose

O [`docker-compose.yml`](docker-compose.yml) sobe o serviço `order` na porta `8080`, lendo o arquivo `.env` e o certificado `rds-ca.pem` da mesma pasta. A memória é limitada a 700 MB, com o heap em até 75% dela (`-XX:MaxRAMPercentage=75`).

```bash
docker compose up -d
docker compose logs -f order
```

### Produção (AWS)

- **Máquina:** EC2 `pato-nego-order` (Amazon Linux 2023, t3.micro), IP privado `10.42.0.13`, na VPC do grupo, região `sa-east-1`.
- **Banco:** a instância RDS PostgreSQL compartilhada pelo grupo. O Order usa o schema `orders` dentro do banco `product`, com o usuário do Product, e não mexe nas tabelas dele.
- **Arquivos na máquina** (em `~/order`): `docker-compose.yml` (enviado pelo CI), `.env` (permissão `600`) e `rds-ca.pem`.
- **Firewall:** a `8080` aceita o gateway e um IP liberado para testes; a `22` aceita o SSH do deploy. Do outro lado, o banco, o Product e o Exchange têm regras de entrada que liberam o Order nas portas `5432`, `8080` e `8000`.
- **Gateway:** a rota `/orders/**` e as variáveis `ORDER_URL` e `ORDER_IP` ficam no `nego-pato`, em `deploy/ec2`.

### Kubernetes

A pasta [`k8s/`](k8s/) tem os manifestos para rodar o Order num cluster (EKS):

| Arquivo | O que é |
|---|---|
| `deployment.yaml` | o Deployment `order`: probes no `/orders/health-check`, recursos (memória de 512 Mi a 700 Mi, CPU de 250m a 1), variáveis do banco vindas de ConfigMap e Secret, e o certificado do RDS montado em `/etc/rds` |
| `service.yaml` | o Service `order` (ClusterIP, porta `8080`), o nome que o gateway usa por padrão (`http://order:8080`) |
| `hpa.yaml` | o autoescalador por CPU (alvo de 50%), de 1 a 3 réplicas |

A imagem do `deployment.yaml` é um texto provisório (`registry.invalid/order-service:bootstrap`), como no Exchange. Quem publica troca pelo endereço real, e o `kubectl set image --local` faz isso sem editar o arquivo.

A configuração e os segredos são criados no cluster, e **não** ficam no Git. O certificado do RDS é público, então vai num ConfigMap:

```bash
kubectl create configmap order-config --from-literal=DATABASE_HOST=ENDPOINT_DO_RDS --from-literal=DATABASE_DB=NOME_DO_BANCO --from-literal=PRODUCT_URL=http://IP_DO_PRODUCT:8080 --from-literal=EXCHANGE_URL=http://exchange:8000
```

```bash
kubectl create secret generic order-secrets --from-literal=DATABASE_USERNAME=USUARIO --from-literal=DATABASE_PASSWORD=SENHA
```

```bash
kubectl create configmap rds-ca --from-file=rds-ca.pem
```

Para aplicar, com a imagem já publicada (por exemplo, no Docker Hub):

```bash
kubectl set image --local -f k8s/deployment.yaml order=docker.io/SEU_USUARIO/order-service:TAG -o yaml | kubectl apply -f -
kubectl apply -f k8s/service.yaml
kubectl rollout status deployment/order
kubectl apply -f k8s/hpa.yaml
```

O HPA precisa do metrics-server no cluster. O k3s já traz, mas o EKS não: é preciso instalá-lo.

Os manifestos foram validados num cluster local (k3s, Kubernetes 1.34), com o Postgres e o Product e o Exchange simulados dentro dele:

- o pod fica `Ready` pelo health-check e não reinicia;
- as variáveis do banco são resolvidas dentro do container, e o certificado aparece em `/etc/rds`;
- de outro pod, o Service responde ao health-check, rejeita a chamada sem `id-account` e cria e lista pedidos;
- sob carga, o HPA subiu de 1 para 3 réplicas em cerca de 45 s.

Nesse teste o banco não tem TLS, então a URL usou `sslmode=disable` no lugar do `verify-full` dos manifestos. É normal ver alguns avisos `Readiness probe failed` quando um pod acaba de subir, antes de o Spring terminar de iniciar. Se o nó for pequeno e a partida ficar mais lenta, aumente o `initialDelaySeconds`.

**Antes de usar no EKS:** os pods precisam alcançar o RDS e o Product, o que depende de a VPC do cluster ser a do grupo (ou de ter conexão com ela) e de os security groups liberarem os nós. O CI ainda **não** publica no cluster: ele faz o deploy na EC2.

### CI/CD (GitHub Actions)

O workflow [`ci-cd.yml`](.github/workflows/ci-cd.yml) roda a cada push e pull request na `main`:

```
pull request ──► test
push na main ──► test ──► deploy
```

| Job | O que faz |
|---|---|
| `test` | baixa este projeto e o contrato, instala o contrato, roda `mvn verify` e guarda o jar |
| `deploy` | constrói a imagem, **varre com o Trivy**, envia para a EC2 por SSH, roda `docker compose up -d` e confere o `/orders/health-check` por até 60 s |

O deploy só acontece depois que os testes passam e nunca roda em pull requests. Se o health-check não responder `200`, o job falha e mostra as últimas linhas do log do container.

**Varredura com o Trivy:** a imagem do Trivy é fixada pela versão (`0.76.2`) e pelo digest, para o CI não mudar sozinho. O passo reprova se houver vulnerabilidade `HIGH` ou `CRITICAL` **que já tenha correção**. Quando isso acontecer, a solução costuma ser subir a versão do Spring Boot no `pom.xml`. Para apenas avisar, sem reprovar, troque `--exit-code 1` por `--exit-code 0` no workflow.

O CI precisa de quatro segredos no repositório (**Settings → Secrets and variables → Actions**):

| Segredo | Conteúdo |
|---|---|
| `EC2_HOST` | endereço público (Elastic IP) da EC2 do Order |
| `EC2_USER` | usuário SSH da EC2 (`ec2-user`) |
| `EC2_SSH_KEY` | chave privada SSH usada só pelo CI |
| `EC2_KNOWN_HOSTS` | saída de `ssh-keyscan` com o endereço da EC2, para verificar a identidade da máquina |

O `.env` e o `rds-ca.pem` não passam pelo GitHub: eles são criados uma vez, direto na EC2. A máquina também precisa ter o Docker e o plugin `docker compose` instalados.

## Decisões de projeto

- **Dois repositórios, contrato e implementação:** é o padrão do curso. Quem consome o Order (como o gateway) usa só o contrato, sem depender do banco nem das regras.
- **Identidade pelo `id-account`:** o Order não valida token. Quem valida é o gateway, e o serviço não é exposto, o que evita validar o JWT duas vezes por requisição.
- **Valores em USD e conversão na leitura:** guardar uma moeda só evita misturar valores, e a cotação pode mudar sem corrigir pedidos antigos.
- **Preço congelado no item:** o pedido é um registro do que foi pago, e não pode mudar quando o preço do produto muda.
- **`BigDecimal` com arredondamento por item:** evita o erro de `double` com dinheiro e garante que os itens somem exatamente o total mostrado.
- **UUID v7 como id:** é ordenado pelo tempo de criação, o que mantém o índice do banco eficiente, e não é adivinhável como um id sequencial.
- **Flyway no lugar do `ddl-auto`:** o esquema do banco é versionado e revisável. O schema próprio `orders` isola as tabelas das do Product, que divide o mesmo banco.
- **Cache em memória, e não Redis:** não exige infraestrutura nova, e como o preço e a cotação mudam pouco, 30 e 60 s de defasagem são aceitáveis. O custo é que cada instância tem o seu cache.
- **Erros com `ResponseStatusException`:** uma classe de exceção por situação seria código demais para cinco casos, e o Spring já devolve o formato padrão.
- **Threads virtuais:** o serviço passa quase todo o tempo esperando o banco e outros serviços, e as threads virtuais deixam isso sair barato.

## Limitações conhecidas

- **Mensagem de validação genérica:** o erro `400` de um corpo inválido não diz qual campo falhou (`Error count: 1`).
- **Listagem sem paginação:** `GET /orders` devolve todos os pedidos do usuário. O resumo é leve, mas para um usuário com milhares de pedidos a resposta cresce.
- **Cache por instância:** com mais de uma instância do Order, cada uma guarda o seu, e a defasagem de preço pode diferir entre elas.
- **Banco compartilhado:** o Order divide o banco e o usuário com o Product. Se a senha do Product mudar, o `.env` do Order precisa ser atualizado.
- **Sem rollout no EKS:** os manifestos em `k8s/` existem e foram validados num cluster local, mas ainda não há cluster, nem registro de imagens, nem passo no CI que publique nele.
- **Sem métricas Prometheus:** hoje só o gateway as expõe, e o grupo não tem um Prometheus para coletá-las. Adicionar ao Order é só configuração, mas custa de 12% a 20% de vazão no pior caso medido.
