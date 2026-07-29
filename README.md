# Yandex Market

Учебное веб-приложение с каталогом товаров, корзиной и оформлением заказов.

## Возможности

- просмотр, поиск и сортировка товаров;
- добавление и удаление товаров из корзины;
- оформление заказа;
- просмотр списка заказов и информации об отдельном заказе.

## Стек

- Java 21;
- Spring Boot, Spring MVC, Thymeleaf;
- Spring Data JPA, PostgreSQL;
- Liquibase, MapStruct;
- Gradle, JUnit 5, Testcontainers, MockMvc.

## Запуск через Docker

Для запуска нужны Docker и Docker Compose.

Сначала соберите jar-файл:

```bash
./gradlew clean bootJar
```

Затем запустите приложение и базу данных:

```bash
docker compose up --build
```

Приложение будет доступно по адресу:

```text
http://localhost:8080/items
```

Для остановки выполните:

```bash
docker compose down
```

## Запуск для разработки

Запустите только PostgreSQL:

```bash
docker compose -f docker-compose.dev.yaml up -d
```

После этого запустите приложение:

```bash
./gradlew bootRun
```

Настройки подключения по умолчанию находятся в `src/main/resources/application.yaml`.
Структура базы и начальные данные создаются Liquibase при старте приложения.

## Тесты

Интеграционные тесты используют Testcontainers, поэтому перед запуском тестов должен работать Docker.

Запуск тестов:

```bash
./gradlew test
```

Полная сборка проекта:

```bash
./gradlew clean build
```
