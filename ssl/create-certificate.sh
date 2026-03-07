#!/bin/bash

# Define o nome dos arquivos de saída
CERTIFICATE_NAME="my_cert"
KEY_NAME="${CERTIFICATE_NAME}.key"
CERT_NAME="${CERTIFICATE_NAME}.crt"

# Gera a chave privada
openssl genpkey -algorithm RSA -out "${KEY_NAME}" -pkeyopt rsa_keygen_bits:2048

# Gera o certificado autoassinado com validade de 5 anos (1825 dias)
openssl req -x509 -new -nodes -key "${KEY_NAME}" -sha256 -days 1825 -out "${CERT_NAME}" -subj "/C=BR/ST=Parana/L=Curitiba/O=MinhaEmpresa/OU=TI/CN=meusite.com"

echo "Certificado e chave gerados com sucesso!"
echo "Certificado: ${CERT_NAME}"
echo "Chave: ${KEY_NAME}"

