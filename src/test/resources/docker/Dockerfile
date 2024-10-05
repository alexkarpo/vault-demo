FROM postgres:15

ENV PGDATA=/var/lib/postgresql/data

# Сначала надо инициализировать базу данных. Выполняем в дефолтной директории
# Переключаемся на пользователя postgres для выполнения initdb
USER postgres
RUN initdb -D /var/lib/postgresql/data

# Вернемся к пользователю root для выполнения остальных команд
USER root

COPY certs/server.crt /var/lib/postgresql/server.crt
COPY certs/server.key /var/lib/postgresql/server.key
COPY certs/root.crt /var/lib/postgresql/root.crt

RUN chmod 600 /var/lib/postgresql/server.key && \
    chown postgres:postgres /var/lib/postgresql/server.key /var/lib/postgresql/server.crt /var/lib/postgresql/root.crt

# Настройка PostgreSQL для использования TLS
# Настройка clientcert=verify-full отвечает за то, что у клиента будет проверяться не только цепочка клиентского сертификата,
# но и соответствие имени пользователя, указанному в сертификате в CN
RUN echo "hostssl all all 0.0.0.0/0 cert clientcert=verify-ca" > /var/lib/postgresql/data/pg_hba.conf && \
    echo "ssl = 'on'" >> /var/lib/postgresql/data/postgresql.conf && \
    echo "ssl_cert_file = '/var/lib/postgresql/server.crt'" >> /var/lib/postgresql/data/postgresql.conf && \
    echo "ssl_key_file = '/var/lib/postgresql/server.key'" >> /var/lib/postgresql/data/postgresql.conf && \
    echo "ssl_ca_file = '/var/lib/postgresql/root.crt'" >> /var/lib/postgresql/data/postgresql.conf
