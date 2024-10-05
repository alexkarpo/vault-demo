#!/usr/bin/env bash
rm -r certs

source gen_certs.sh

docker build -f "Dockerfile2" -t postgres-tls .
