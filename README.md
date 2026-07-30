# Yandex Market — проект восьмого спринта

Реактивная витрина интернет-магазина с авторизацией покупателей, отдельным
OAuth2-защищённым сервисом платежей и Redis-кешем товаров. Покупатели входят в
`market-app` по логину и паролю, а `market-app` обращается к `payment-service`
по OAuth2 Client Credentials через Keycloak.

## Архитектура

В Gradle-мультипроекте остаются ровно два подпроекта:

```text
.
├── market-app/                 # WebFlux, Thymeleaf, Security, R2DBC, Redis
├── payment-service/            # WebFlux OAuth2 Resource Server
├── keycloak/                   # импорт realm для authorization server
├── openapi/payment-api.yaml    # единый контракт платёжного API
├── docker-compose.dev.yaml     # Keycloak, PostgreSQL и Redis для bootRun
├── docker-compose.yaml         # полный стенд
└── Dockerfile                  # образы двух приложений
```

Keycloak является инфраструктурным контейнером, а не третьим Gradle-модулем.
OpenAPI Generator во время сборки создаёт реактивный `WebClient` для
`market-app` и WebFlux delegate API для `payment-service`. Сгенерированные
файлы находятся в `build/generated` и не коммитятся.

## Стек

- Java 21, Gradle Wrapper 8.14.4 и Spring Boot 3.5;
- Spring WebFlux и Reactor Netty;
- Spring Security: form login, BCrypt, CSRF и OAuth2;
- Spring Data R2DBC и PostgreSQL;
- Spring Data Redis Reactive;
- Thymeleaf с Spring Security dialect;
- Keycloak 26.7.0;
- OpenAPI 3.0.3 и OpenAPI Generator;
- JUnit 5, Spring Boot Test, Spring Security Test, Reactor Test и Testcontainers.

В runtime нет Spring MVC, Servlet API, Spring Data JPA, Hibernate ORM,
JDBC-драйвера или Liquibase.

## Требования

- JDK 21;
- Docker с запущенным Docker Engine;
- свободные порты `8080`, `8081`, `8082`, `5432` и `6379`.

Локально установленный Gradle не нужен.

## Секреты локального окружения

В репозитории нет рабочего OAuth2 client secret. Создайте локальный `.env`:

```bash
cp .env.example .env
```

Замените оба значения в `.env`. Этот файл исключён из Git. Для запуска из
исходников экспортируйте значения в текущий shell:

```bash
set -a
source .env
set +a
```

Значения предназначены только для локального стенда. В production следует
использовать secret manager, TLS и production-режим Keycloak.

## Тесты и сборка

Чистая генерация OpenAPI и запуск всех unit-, security- и интеграционных тестов:

```bash
./gradlew clean test
```

Полная проверка и сборка двух executable JAR:

```bash
./gradlew clean build
```

Результат:

```text
market-app/build/libs/market-app.jar
payment-service/build/libs/payment-service.jar
```

Интеграционные тесты используют PostgreSQL, Redis и Keycloak в Testcontainers.
Тесты одного типа переиспользуют Spring-контекст; `@DirtiesContext` не
используется, а состояние очищается между сценариями.

Проверяются, в частности:

- вход с BCrypt-паролем, неверный пароль, CSRF и полный logout;
- доступ анонимного пользователя только к каталогу и карточке товара;
- отсутствие приватных ссылок и элементов управления в анонимном HTML;
- независимые корзины, счётчики товаров и заказы Alice и Bob;
- невозможность открыть заказ другого пользователя по известному ID;
- получение OAuth2-токена по Client Credentials и Bearer-запрос;
- `401` без токена, `403` без нужного scope и проверка `iss`, `aud`, `azp`;
- независимые платёжные балансы и идемпотентность каждого покупателя;
- задержанный повтор при временной недоступности payment-service;
- фиксация `PENDING`-заказа до HTTP-вызова и восстановление после двух
  потерянных ответов без изменения состава корзины;
- Redis cache hit/miss/TTL/fallback и полный поток покупки.

## Полный запуск в Docker

После создания `.env`:

```bash
docker compose up --build
```

Сервисы:

- витрина: [http://localhost:8080/items](http://localhost:8080/items);
- payment API: `http://localhost:8081/api/v1`;
- Keycloak: [http://localhost:8082](http://localhost:8082);
- health payment-service:
  [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health).

`market-app` стартует только после readiness Keycloak, PostgreSQL, Redis и
`payment-service`. Health endpoint платежного сервиса открыт, бизнес-эндпоинты
защищены OAuth2.

Остановка с сохранением PostgreSQL:

```bash
docker compose down
```

Полная очистка стенда:

```bash
docker compose down --volumes --remove-orphans
./gradlew --stop
```

## Запуск приложений из исходников

Поднимите инфраструктуру:

```bash
docker compose -f docker-compose.dev.yaml up -d
```

В первом терминале, где загружен `.env`, запустите payment-service:

```bash
./gradlew :payment-service:bootRun
```

Во втором терминале с теми же переменными:

```bash
./gradlew :market-app:bootRun
```

Для остановки:

```bash
docker compose -f docker-compose.dev.yaml down
./gradlew --stop
```

## Покупатели

Два учебных пользователя загружаются в PostgreSQL идемпотентно:

| Логин | Пароль |
|---|---|
| `alice` | `alice123` |
| `bob` | `bob123` |

В БД сохраняются только BCrypt-хеши с cost factor 12. У пользователя есть
внутренний числовой ID для корзины и заказов и отдельный неизменяемый UUID
платёжного счёта.

Анонимный пользователь может открыть только `/`, `/items` и `/items/{id}`.
Корзина, заказы, изменение количества и покупка защищены одновременно:

- правилами `SecurityWebFilterChain` на сервере;
- CSRF для изменяющих form-запросов;
- `sec:authorize` в HTML-шаблонах.

Logout выполняется POST-запросом с CSRF, очищает `SecurityContext`, инвалидирует
WebSession и удаляет cookie `SESSION`.

## Изоляция данных

Каждая корзина и каждый заказ имеют владельца. Partial unique index допускает
не более одной открытой корзины (`ACTIVE` или `CHECKOUT`) на пользователя, а
составной внешний ключ заказа не позволяет связать заказ с чужой корзиной.
Внешние чтения и переходы состояния выбирают корзины и заказы по `user_id`;
внутренние операции с позициями получают только уже проверенный `basket_id` или
`order_id` внутри той же сервисной операции. Составные FK дополнительно
закрепляют владельца на уровне PostgreSQL.

При обновлении существующего тома седьмого спринта прежние данные сохраняются за
Alice. Дедупликация старых активных корзин выполняется отдельно внутри каждого
пользователя.

Общий Redis snapshot содержит только данные товара. Количество товара в корзине
не кешируется и читается из PostgreSQL отдельно для текущего пользователя;
анонимный каталог всегда получает количество `0`.

## OAuth2 и платёжные счета

В Keycloak зарегистрированы:

- confidential client `market-app` с service account;
- resource client `payment-service`;
- scopes `payment.read` и `payment.write`;
- audience `payment-service`.

`payment-service` проверяет подпись JWT, срок действия, точный issuer, audience,
authorized party `market-app` и нужный scope. `GET /balance` требует
`payment.read`, а `POST /payment` — `payment.write`.

Client Credentials удостоверяет приложение, а не вошедшего покупателя. Поэтому
`market-app` берёт UUID счёта только из серверного authenticated principal и
передаёт его в обязательном заголовке `X-Customer-Id`. Этот идентификатор не
принимается из HTML form или query-параметра. `payment-service` доверяет
заголовку только после успешной проверки токена `market-app`.

В Docker Keycloak публикует canonical issuer
`http://localhost:8082/realms/market`. Приложения получают токен и JWK по
внутренним Docker-адресам, но проверяют стабильное значение `iss`, поэтому
токены одинаково работают через полный стенд и при ручной проверке с хоста.

## Ручная проверка payment API

Загрузите `.env`, затем получите токен:

```bash
TOKEN="$(
  curl --fail --silent \
    -u "market-app:${PAYMENT_OAUTH_CLIENT_SECRET}" \
    -d grant_type=client_credentials \
    -d 'scope=payment.read payment.write' \
    http://localhost:8082/realms/market/protocol/openid-connect/token |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
)"
```

Без токена API вернёт `401`:

```bash
curl -i \
  -H 'X-Customer-Id: 85a65fde-0492-4dcf-b1f4-dbc8f901ae72' \
  http://localhost:8081/api/v1/balance
```

Баланс Alice с токеном:

```bash
curl --fail \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'X-Customer-Id: 85a65fde-0492-4dcf-b1f4-dbc8f901ae72' \
  http://localhost:8081/api/v1/balance
```

Пример платежа:

```bash
curl --fail -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'X-Customer-Id: 85a65fde-0492-4dcf-b1f4-dbc8f901ae72' \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "d989189e-0e6d-4e3d-9792-673629702050",
    "amount": 1499.90
  }' \
  http://localhost:8081/api/v1/payment
```

Баланс хранится отдельно для каждого UUID в копейках в `AtomicLong`. CAS не
позволяет уйти в минус при конкуренции. Идемпотентность определяется парой
`(customerId, requestId)`: повтор той же суммы не списывает деньги второй раз,
а повтор с другой суммой возвращает `409 IDEMPOTENCY_CONFLICT`.

## Оформление заказа

Checkout разделён на короткие транзакции и сетевой этап:

1. `market-app` блокирует `ACTIVE`-корзину, сохраняет `PENDING`-заказ, неизменный
   snapshot, сумму, UUID платёжного счёта и случайный `payment_request_id`, затем
   переводит корзину в `CHECKOUT` и фиксирует транзакцию.
   Итоговая сумма вычисляется из сохранённого snapshot, поэтому она не расходится
   с позициями при конкурентном изменении цены товара.
2. Одна из конкурентных попыток атомарно получает ограниченную по времени
   платёжную аренду. OAuth2-защищённый HTTP-платёж выполняется уже без открытой
   DB-транзакции; остальные одновременные `/buy` не отправляют второй запрос.
3. После подтверждения отдельная короткая транзакция переводит заказ в
   `COMPLETED`, а корзину — в `CLOSED`. Только такие заказы видны в истории.

При временной транспортной ошибке выполняется одна неблокирующая повторная
попытка с задержкой `PAYMENT_RETRY_DELAY`. Если оба ответа потеряны,
`PENDING`/`CHECKOUT` сохраняются: корзина остаётся видимой, но её состав нельзя
изменить, а следующий `/buy` повторяет исходную сумму с тем же ключом
идемпотентности. Однозначный отказ — недостаток средств, ошибка валидации или
OAuth2 — удаляет ожидающий заказ и возвращает корзину в `ACTIVE`, только если до
него не было неоднозначной попытки; новая покупка получает новый ключ. После
потерянного ответа сохраняется признак возможного обращения к payment-service,
поэтому даже последующий однозначный отказ не удаляет прежний платёжный intent
до сверки. `IDEMPOTENCY_CONFLICT` также считается неопределённым результатом и
не размораживает корзину автоматически. Если процесс аварийно завершился,
просроченную платёжную аренду может безопасно забрать следующая попытка; её срок
вычисляется только по часам PostgreSQL.

Состояние payment-service хранится в памяти одного экземпляра и возвращается к
начальному значению после перезапуска. Для production понадобились бы
персистентный ledger и saga/outbox.

## Конфигурация

### Market-app

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `HOST` | `localhost` | PostgreSQL host |
| `PORT` | `5432` | PostgreSQL port |
| `POSTGRES_DB` | `market_db` | имя БД |
| `POSTGRES_USER` | `dev` | пользователь БД |
| `POSTGRES_PASSWORD` | `qwerty` | пароль БД |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `PRODUCT_CACHE_TTL` | `2m` | TTL каталога |
| `PAYMENT_SERVICE_BASE_URL` | `http://localhost:8081` | payment-service URL |
| `PAYMENT_CONNECT_TIMEOUT` | `1s` | connect timeout |
| `PAYMENT_RESPONSE_TIMEOUT` | `2s` | response timeout |
| `PAYMENT_RETRY_DELAY` | `200ms` | задержка единственного повтора |
| `PAYMENT_OAUTH_TOKEN_URI` | Keycloak на `localhost:8082` | token endpoint |
| `PAYMENT_OAUTH_CLIENT_ID` | `market-app` | OAuth2 client ID |
| `PAYMENT_OAUTH_CLIENT_SECRET` | обязательна | OAuth2 client secret |

### Payment-service

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `PAYMENT_SERVICE_PORT` | `8081` | HTTP-порт |
| `PAYMENT_INITIAL_BALANCE` | `1000000.00` | начальный баланс каждого счёта |
| `PAYMENT_OAUTH_ISSUER_URI` | Keycloak на `localhost:8082` | допустимый issuer |
| `PAYMENT_OAUTH_JWK_SET_URI` | Keycloak на `localhost:8082` | JWK endpoint |
| `PAYMENT_OAUTH_AUDIENCE` | `payment-service` | обязательный audience |
| `PAYMENT_OAUTH_AUTHORIZED_CLIENT_ID` | `market-app` | обязательный `azp` |

Начальный баланс должен быть неотрицательным и иметь не более двух знаков после
запятой. Все таймауты и задержка повтора должны быть положительными.

## Web-маршруты

| Метод | Маршрут | Доступ | Назначение |
|---|---|---|---|
| `GET` | `/`, `/items` | все | каталог, поиск, сортировка, пагинация |
| `GET` | `/items/{id}` | все | карточка товара |
| `POST` | `/items`, `/items/{id}` | пользователь | изменить количество |
| `GET`, `POST` | `/cart/items` | пользователь | корзина и её изменение |
| `POST` | `/buy` | пользователь | оплатить и оформить корзину |
| `GET` | `/orders`, `/orders/{id}` | пользователь | собственные заказы |
| `POST` | `/logout` | пользователь | полный logout |

Параметр `action` принимает `PLUS`, `MINUS` или `DELETE`; `DELETE` разрешён
только на странице корзины. Каталог поддерживает сортировки `NO`, `ALPHA`,
`PRICE` и размеры страницы 2, 5, 10, 20, 50 и 100.
