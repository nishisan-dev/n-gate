# Walkthrough: Benchmark Nginx vs Javalin Proxy

## Resumo das Modificações

Nesta etapa, adicionamos uma nova camada no ecossistema `docker-compose` do `inventory-adapter` a fim de aprimorar nosso teste de benchmark e realizar comparações mais paritárias. Foi implementado um contêiner separado contendo um **Nginx configurado como Proxy Reverso**, atuando como um intermediário idêntico ao proxy feito com Javalin.

### Alterações Realizadas

1. **Adição do serviço Nginx Proxy:**
   - Foi criado um novo arquivo de configuração de proxy (`compose/nginx-proxy/default.conf`) que define o repasse dos cabeçalhos primordiais (Host, X-Real-IP, X-Forwarded-For, etc.) e mapeia tráfego para a URL interna do `static-backend` na porta 8080. 
   - Atualizado o `docker-compose.yml` para instanciar o serviço `nginx-proxy`, expondo-o no Host na porta `4080`.
2. **Atualização do Script de Benchmark (`scripts/benchmark.py`):**
   - O script foi evoluído de uma verificação simples (Baseline x Target) para uma verificação em matriz de múltiplos endpoints (`ENDPOINTS`), iterando sobre o Nginx estático (baseline nativo), o Nginx em modo proxy, e o Javalin.
   - Foram corrigidos bugs do script para exibir o relatório final exibindo os overlays pareados e proporcionais e exportando dois arquivos JSON segregados: por requests fixos e por tempo de estresse.

---

## Resultados do Benchmark (10s Sustentados)

Abaixo estão dispostos os resultados de sobrecarga comparativos em fase controlada sustentada por tempo (10 segundos):

| Nível de Concorrência (c) | Nginx Configurado como Proxy | Adapater Javalin (+Loom Virt) |
| :-----------------------: | :--------------------------: | :---------------------------: |
| **1** | + 0.36ms | **+ 0.55ms** |
| **10** | + 0.46ms | **+ 2.85ms** |
| **50** | + 0.92ms | **+ 10.25ms** |
| **100** | + 2.39ms | **+ 19.27ms** |
| **500** | + 10.85ms | **+ 117.23ms** |

### Reflexões e Próximos Passos

Os novos resultados denotam o grau real de distanciamento que a máquina virutal Java + Javalin imprime na rede perante uma implementação puramente focada em evento multiplexado orientada em C (os ganhos em Virtual Threads amenizaram, mas o comparativo lado a lado mostra a natureza real).

Como esperado, **o Nginx Proxy exibe uma resiliência impecável mesmo a grandes níveis de concorrência massiva**, sofrendo menos degradação (mesmo rodando o container puramente un-tuned). Agora temos a métrica correta não contra o processo bruto de retorno HTTP, mas contra de fato outro proxy atuando da mesma forma.

Você pode revisar o arquivo na base de logs no código gerado em `scripts/benchmark_results_timed.json` para observar as métricas exatas por req/s e quartis extraídos ao lado do tail latency.
