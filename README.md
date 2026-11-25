# Java: цикл разработки

Spring Boot API сообщений с JDBC, Actuator, JSON-логами и CI.

## Запуск

Java 17+.

```sh
./gradlew clean build
./gradlew bootRun --args='--spring.profiles.active=demo'
```

В Windows: `gradlew.bat`. Профиль `demo` использует встроенную H2 в режиме PostgreSQL.
Для PostgreSQL запустите `docker compose up -d`, затем приложение без профиля demo.
Подключение задаётся переменными `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

API: `http://localhost:8080/api/messages`.

```sh
curl -X POST http://localhost:8080/api/messages -H 'Content-Type: application/json' -d '{"content":"Hello","author":"Ivan"}'
```

## Наблюдаемость

- `http://localhost:8081/actuator/health`
- `http://localhost:8081/actuator/info`
- `http://localhost:8081/actuator/prometheus`

В Prometheus метрика `messages_created_total` учитывает каждое новое сообщение после
успешного commit транзакции, в том числе пакетную вставку. Откат не увеличивает счётчик.
Counter `messages.created` преобразуется Prometheus registry в `messages_created_total`.
Это накопительный счётчик событий текущего процесса, а не число строк в таблице.
JSON-логи выводятся в stdout через LogstashEncoder; баннер отключён.

## CI

`.github/workflows/ci-build.yml` запускается при push в main и pull request в main:
checkout, Java 17, `./gradlew clean build`, статус тестов и публикация JAR и отчётов.

Тесты используют H2 и проверяют API, транзакции, Actuator, Prometheus и JSON-логирование.

Spring Boot 3.2.12 and its compatible Micrometer/Prometheus client preserve the exact required messages_created_total metric name. Build and CI use Java 17.
