# Pré-geração de LODs no servidor (`/dh pregen`) — AOTM-19

**Problema:** o servidor só envia ao cliente LODs que já estão no banco dele, e esse banco só recebe o que algum jogador carregou.
Com a geração distante desligada (padrão do Mirai, D-07), quem ativa o DH precisa sair voando para os LODs aparecerem.

**Solução:** um admin constrói os LODs no servidor antes, com `/dh pregen`. Quando o jogador ativar o DH, o sync servidor → cliente
(`FullDataSourceRequestHandler.readSync`) já encontra os dados prontos. Os clientes **continuam sem poder pedir geração**.

## Uso (servidor dedicado, op)

```text
/dh pregen start <dimensão> <blocoX> <blocoZ> [raioEmChunks=32] [existing|generate]
/dh pregen status
/dh pregen stop
```

* `<dimensão>`: id numérico (`0`, `-1`, `1`, …) ou nome do provider.
* **`existing` (padrão na 1.7.10):** só converte seções cujos chunks, mais a borda de 1 chunk que o gerador carrega, **já estão
  salvos** nos `.mca`. Seções incompletas são puladas (contador `skipped`). Não cria terreno novo. Ideal depois de pré-gerar o mundo
  com outro mod.
* **`generate`:** comportamento do upstream: chunks ausentes são gerados pelo gerador do mundo (`loadChunk`, tickets).
  Esse caminho **não passou** pelos gates do Crucible; use com o servidor em janela de manutenção.
* **Retomada:** o progresso fica em `<pasta DH da dimensão>/mirai-pregen-progress.properties`. `stop`, falha ou reinício preservam o
  arquivo, e o mesmo `start` (mesmos argumentos) continua dali. O arquivo é apagado ao concluir.

## Proteções

| Proteção | Comportamento |
|---|---|
| Fila de geração só durante o pregen | `AbstractDhServerLevel.shouldDoWorldGen()` = config **ou** pregen ativo naquela dimensão. A config e a `SessionConfig` dos clientes não mudam |
| Pausa por TPS | Não submete seções novas enquanto o tick médio (100 ticks) do servidor passar de 45 ms; retoma sozinho |
| Concorrência | No máximo `numberOfThreads` seções em voo |
| Seção travada | Timeout de 2 min por seção, contada como falha, e o pregen segue (não depende de cache ser tocado) |
| Fila ausente | Espera até 30 s; depois falha com mensagem clara (antes travava em silêncio) |
| Conclusão | Só depois de não haver nada em voo (antes encerrava com tarefas rodando, e a fila era desligada embaixo delas) |
| Descarregar dimensão / parar servidor | Cancela o pregen da dimensão; o progresso fica salvo |

## Chaves JVM

* `-Dakashic.mirai.serverPregen=false`: recusa qualquer `/dh pregen start` (rollback).
* `-Dakashic.mirai.pregenMaxTickMs=45`: limite do tick médio; `0` desliga a pausa.

## Limites conhecidos

* `existing` lê o cabeçalho do `.mca` direto do disco (somente leitura; não usa `RegionFileCache`, que criaria arquivos). Chunks
  carregados mas ainda não salvos contam como ausentes. Um chunk salvo sem *population* ainda pode ser populado ao carregar (vanilla).
* Só o comando da 1.7.10 aceita o modo; versões novas mantêm a assinatura do upstream (`generate`).
* **Gate pendente (AOTM-10/AOTM-19):** nada disso rodou num servidor Crucible real. Os testes são headless (`PregenTaskTest`,
  `RegionFileChunkIndexTest`).
