# Limpeza de Referências Legadas e Regeneração SSL

Remoção de menções legadas (Telefonica, Netcompass, TELCOSTACK) do codebase e regeneração dos artefatos SSL com identidade "nishisan".

## Proposed Changes

### Remoção da pasta `.idea`

#### [DELETE] `.idea/`

A pasta já está no `.gitignore` e **não está tracked** no Git. Será deletada localmente para higiene do workspace.

---

### Limpeza de comentários legados em código Java

#### [MODIFY] [OAuthClientManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/auth/OAuthClientManager.java)

- **Linha 204**: Comentário `// Tokens exclusicos da Telefonica` → `// Headers específicos do SSO (app-key / oam)`
- **Linha 267**: Mesmo comentário → mesma substituição

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

- **Linhas 583-587**: Comentário mencionando "TMF-639 do Netcompass" → Reescrever como comentário genérico sobre o ponto de extensão para transformação de response
- **Linhas 201-202, 253-254, 301-302**: Comentários com `TELCOSTACK-` → Remover linhas comentadas por completo (código morto)

#### [MODIFY] [HttpRequestAdapter.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpRequestAdapter.java)

- **Linha 46**: Linha comentada `x-netcompass-uid` → Remover (código morto)

---

### Regeneração SSL Keystore

#### [MODIFY] [create-certificate.sh](file:///home/lucas/Projects/n-gate/ssl/create-certificate.sh)

Atualizar o `-subj` do certificado:
- `O=MinhaEmpresa` → `O=Nishisan`
- `OU=TI` → `OU=Dev`
- `CN=meusite.com` → `CN=ngate.nishisan.dev`

#### Regenerar artefatos SSL

Após atualizar o script, executar:
1. Remover arquivos antigos: `keystore.jks`, `keystore.p12`, `my_cert.crt`, `my_cert.key`, `truststore.jks`
2. Executar `create-certificate.sh` para gerar novo cert/key
3. Gerar `keystore.p12`: `openssl pkcs12 -export -in my_cert.crt -inkey my_cert.key -out keystore.p12 -name ngate -passout pass:changeit`
4. Gerar `keystore.jks`: `keytool -importkeystore -srckeystore keystore.p12 -srcstoretype PKCS12 -destkeystore keystore.jks -deststoretype JKS -srcstorepass changeit -deststorepass changeit`
5. Gerar `truststore.jks`: `keytool -import -alias ngate -file my_cert.crt -keystore truststore.jks -storepass changeit -noprompt`

## Verification Plan

### Build

Executar `mvn clean package -DskipTests` para garantir que as mudanças nos comentários não quebraram a compilação.

### Grep de validação

Executar grep por `vivo`, `telefonica`, `netcompass`, `TELCOSTACK` e `MinhaEmpresa` para confirmar que zero referências permanecem.

### Certificado SSL

Verificar o CN do novo certificado com:
```bash
openssl x509 -in ssl/my_cert.crt -noout -subject
```
Deve exibir `CN=ngate.nishisan.dev`.
