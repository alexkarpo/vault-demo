#!/usr/bin/env bash
mkdir certs

pushd certs || exit

#### Генерация корневого сертификата
openssl genrsa -out root.key 2048
openssl req -x509 -new -nodes -key root.key -sha256 -days 365 -out root.crt -subj "/CN=Root CA"

#### Генерация серверного сертификата БД
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=postgres"
openssl x509 -req -in server.csr -CA root.crt -CAkey root.key -CAcreateserial -out server.crt -days 365 -sha256

#### Генерация клиентского сертификата для подключения к БД
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=client"
openssl x509 -req -in client.csr -CA root.crt -CAkey root.key -CAcreateserial -out client.crt -days 365 -sha256

popd || exit
