# Yandex Market — проект седьмого спринта

Реактивная витрина интернет-магазина с отдельным RESTful-сервисом платежей и
Redis-кешем товаров. Оба приложения находятся в одном Gradle-мультипроекте,
собираются на Java 21 и запускаются на Reactor Netty.

## Структура проекта

```text
.
├── market-app/                 # WebFlux + Thymeleaf + R2DBC + Redis
├── payment-service/            # реактивный JSON API платежей
├── openapi/payment-api.yaml    # единый контракт интеграции
├── build.gradle                # общие настройки мультипроекта
├── settings.gradle             # два подпроекта
├── Dockerfile                  # образы market-app и payment-service
└── docker-compose.yaml         # полный стенд приложения
```

OpenAPI Generator во время каждой чистой сборки создаёт:

- реактивный WebClient-клиент в `market-app/build/generated/openapi`;
- WebFlux-контроллер и модели в `payment-service/build/generated/openapi`.

Сгенерированные файлы не коммитятся. Реализация market-app использует
сгенерированный `PaymentsApi`, а payment-service реализует сгенерированный
`PaymentsApiDelegate`.

## Стек

- Java 21 и Gradle Wrapper 8.14.4;
- Spring Boot 3.5, Spring WebFlux, Reactor Netty;
- Spring Data R2DBC и PostgreSQL;
- Spring Data Redis Reactive и Redis;
- Thymeleaf;
- OpenAPI 3.0.3 и OpenAPI Generator;
- JUnit 5, Spring Boot Test, WebTestClient, Reactor Test и Testcontainers.

В runtime нет Spring MVC, Servlet API, Spring Data JPA, Hibernate ORM,
блокирующего JDBC-драйвера или Liquibase.

## Требования

- JDK 21;
- Docker с запущенным Docker Engine — для интеграционных тестов и полного стенда.

Локально установленный Gradle не нужен.

## Тесты и сборка

Чистая генерация OpenAPI и запуск всех unit-, web- и интеграционных тестов:

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

Интеграционные тесты автоматически поднимают PostgreSQL и Redis через
Testcontainers. Тесты одного приложения повторно используют Spring-контекст и
не применяют `@DirtiesContext`; состояние БД, кеша и тестового платёжного шлюза
очищается между сценариями.

Покрыты, в частности:

- cache hit/miss, typed JSON-сериализация, TTL, eviction и fallback при ошибке Redis;
- поиск, сортировка, пагинация и актуальные количества товаров в корзине;
- generated HTTP-клиент и JSON-контракт payment-service;
- валидация, недостаток средств, точное списание, конкурентные платежи и
  идемпотентность;
- недоступность payment-service, disabled-кнопка покупки и откат заказа при
  ошибке оплаты;
- полный поток каталог → корзина → оплата → заказ.

## Локальный запуск из исходников

Поднять PostgreSQL и Redis:

```bash
docker compose -f docker-compose.dev.yaml up -d
```

В первом терминале запустить сервис платежей:

```bash
./gradlew :payment-service:bootRun
```

Во втором терминале запустить витрину:

```bash
./gradlew :market-app:bootRun
```

Адреса:

- витрина: [http://localhost:8080/items](http://localhost:8080/items);
- баланс: [http://localhost:8081/api/v1/balance](http://localhost:8081/api/v1/balance).

Остановка инфраструктуры:

```bash
docker compose -f docker-compose.dev.yaml down
```

После `./gradlew clean build` приложения можно запустить как JAR:

```bash
java -jar payment-service/build/libs/payment-service.jar
java -jar market-app/build/libs/market-app.jar
```

## Запуск полного стенда в Docker

Собрать образы и запустить market-app, payment-service, PostgreSQL и Redis:

```bash
docker compose up --build
```

Market-app стартует после успешных healthcheck PostgreSQL, Redis и
payment-service.

Остановить контейнеры, сохранив данные PostgreSQL:

```bash
docker compose down
```

Удалять том следует только когда данные больше не нужны:

```bash
docker compose down -v
```

## Конфигурация

### Market-app

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `HOST` | `localhost` | адрес PostgreSQL |
| `PORT` | `5432` | порт PostgreSQL |
| `POSTGRES_DB` | `market_db` | имя базы |
| `POSTGRES_USER` | `dev` | пользователь БД |
| `POSTGRES_PASSWORD` | `qwerty` | пароль БД |
| `REDIS_HOST` | `localhost` | адрес Redis |
| `REDIS_PORT` | `6379` | порт Redis |
| `REDIS_CONNECT_TIMEOUT` | `1s` | таймаут подключения к Redis |
| `REDIS_TIMEOUT` | `1s` | таймаут команды Redis |
| `PRODUCT_CACHE_TTL` | `2m` | время жизни каталога в кеше |
| `PAYMENT_SERVICE_BASE_URL` | `http://localhost:8081` | URL payment-service |
| `PAYMENT_CONNECT_TIMEOUT` | `1s` | таймаут подключения к payment-service |
| `PAYMENT_RESPONSE_TIMEOUT` | `2s` | таймаут ответа payment-service |

### Payment-service

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `PAYMENT_SERVICE_PORT` | `8081` | HTTP-порт |
| `PAYMENT_INITIAL_BALANCE` | `1000000.00` | начальный баланс |

Начальный баланс должен быть неотрицательным и содержать не более двух знаков
после запятой.

## Redis-кеш товаров

Каталог хранится под ключом `market-app:products:v1` как typed JSON snapshot с
TTL две минуты. Кешируются `id`, путь к изображению, название, описание и цена.
Количество товара в корзине не кешируется: оно всегда читается из PostgreSQL и
объединяется с данными каталога.

Один snapshot используется для:

- списка товаров, после чего в Java применяются поиск, стабильная сортировка и
  пагинация;
- карточки товара;
- наполнения строк корзины актуальными описанием и ценой.

При cache miss каталог читается из PostgreSQL и записывается в Redis. Если Redis
недоступен, запрос продолжает работать с данными БД без повторной попытки записи
в рамках того же запроса; ошибки БД при этом не маскируются. Для очистки
используется только точный ключ, без `KEYS` и `FLUSHDB`.

Исходный список товаров загружается из
`market-app/src/main/resources/db/data.sql`. Схема создаётся скриптом
`market-app/src/main/resources/db/schema.sql`.

## Платежи и оформление заказа

OpenAPI-контракт находится в `openapi/payment-api.yaml`.

| Метод | Маршрут | Назначение |
|---|---|---|
| `GET` | `/api/v1/balance` | получить текущий баланс |
| `POST` | `/api/v1/payment` | списать сумму |

Пример платежа:

```bash
curl -X POST http://localhost:8081/api/v1/payment \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "d989189e-0e6d-4e3d-9792-673629702050",
    "amount": 1499.90
  }'
```

Баланс хранится в копейках в `AtomicLong`. CAS-списание не позволяет уйти в
минус даже при конкурентных запросах. `requestId` является ключом
идемпотентности: повтор с той же суммой не списывает деньги ещё раз, а повтор с
другой суммой возвращает `409 IDEMPOTENCY_CONFLICT`.

JSON-запросы валидируются строго: обязательные поля, положительная сумма с
точностью до копейки и отсутствие неизвестных полей. Market-app дополнительно
проверяет, что `requestId` и сумма успешного ответа совпадают с отправленным
запросом.

На странице корзины кнопка покупки доступна только при достаточном балансе.
Недостаток средств и недоступность payment-service показываются как разные
состояния. `POST /buy` всегда выполняет серверный платёж повторно, поэтому
проверка кнопки не является единственной защитой.

Во время checkout активная корзина блокируется в реактивной R2DBC-транзакции,
создаются заказ и снимок позиций, затем выполняется идемпотентный платёж. Ошибка
платежа откатывает заказ и оставляет корзину активной. При неоднозначной
транспортной ошибке выполняется одна подтверждающая попытка с тем же
`requestId`. Если платёж уже подтверждён, а локальная транзакция завершилась
ошибкой, market-app проверяет, не был ли заказ зафиксирован, и при необходимости
один раз повторяет локальный checkout с тем же ключом идемпотентности.

Payment-service хранит баланс и таблицу идемпотентности в памяти одного
экземпляра. После его перезапуска состояние возвращается к значению из
конфигурации. Для учебного задания это выбранная модель; production-вариант
потребовал бы персистентный платёжный ledger и saga/outbox, чтобы полностью
устранить неопределённость при одновременной потере обоих ответов на
идемпотентные запросы или перезапуске payment-service.

## Web-маршруты market-app

| Метод | Маршрут | Назначение |
|---|---|---|
| `GET` | `/` или `/items` | витрина, поиск, сортировка и пагинация |
| `POST` | `/items` | изменить количество товара из витрины |
| `GET` | `/items/{id}` | карточка товара |
| `POST` | `/items/{id}` | изменить количество из карточки |
| `GET` | `/cart/items` | корзина, баланс и возможность оплаты |
| `POST` | `/cart/items` | увеличить, уменьшить или удалить товар |
| `POST` | `/buy` | оплатить и оформить активную корзину |
| `GET` | `/orders` | история заказов |
| `GET` | `/orders/{id}` | страница заказа |

Параметр `action` принимает `PLUS`, `MINUS` или `DELETE`; `DELETE` разрешён
только на странице корзины. Витрина поддерживает сортировки `NO`, `ALPHA`,
`PRICE` и размеры страницы 2, 5, 10, 20, 50 и 100.
