Демонстрация использования Testcontainers при работе с БД Postgres и Vault. 

В тестах собраны разные сценарии взаимодействия с контейнерами.

Перед началом убедитесь, что у вас установлен Docker, и запустите скрипт **_src/test/resources/docker/docker-build.sh_**. Он сгенерит сертификаты и соберет образ БД _postgres-tls_.
Этот образ используется во всех демонстрационных тестах.
