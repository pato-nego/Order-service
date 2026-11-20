# Order-service

## API


| Método | Rota                        | Resposta                                                                                                                              |
| --------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| POST    | `/orders`                   | 201 com o pedido. 400 se o produto não existir, a lista estiver vazia ou a quantidade for menor que 1. 503 se o Product estiver fora |
| GET     | `/orders`                   | 200 com `id`, `date` e `total` de cada pedido do usuário, do mais antigo ao mais novo                                                |
| GET     | `/orders/{id}`              | 200 com o pedido. 404 se não existir ou for de outro usuário                                                                        |
| GET     | `/orders/{id}?currency=BRL` | Igual, com os totais convertidos. 422 se a moeda não for suportada. 503 se o Exchange estiver fora                                   |
| GET     | `/orders/health-check`      | 200 sem corpo (probe do orquestrador, direto no serviço, sem token)                                                                  |

Sem o header `id-account` as rotas de pedido respondem 401.

## Compilar e testar

Instale primeiro o contrato e depois compile o serviço (JDK 25 e Maven):

```sh
mvn -f ../Order/pom.xml clean install
mvn clean package
```

## Deploy

O workflow `.github/workflows/ci-cd.yml` roda em todo push e pull request na `main`: instala o contrato [Order](https://github.com/pato-nego/Order), compila e testa o serviço. No push, constrói a imagem, envia por SSH para a EC2 do Order, sobe o container com o `docker-compose.yml` e confere o `/orders/health-check`.

Secrets do repositório (Settings > Secrets and variables > Actions):

| Secret | Valor |
| --- | --- |
| `EC2_HOST` | IP público (Elastic IP) da EC2 do Order |
| `EC2_USER` | usuário SSH da EC2 (`ec2-user` no Amazon Linux) |
| `EC2_SSH_KEY` | chave privada SSH |
| `EC2_KNOWN_HOSTS` | saída de `ssh-keyscan <EC2_HOST>` |

No servidor, instale o Docker com o plugin `docker compose` e, em `~/order/`, crie o `.env` a partir do `.env.example` e coloque o `rds-ca.pem` ao lado do compose.
