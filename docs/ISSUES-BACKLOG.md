# Backlog de Issues do AOTM

Fonte única dos Issues do GitHub: `scripts/seed-github-issues.py` lê **este arquivo** e cria labels, milestones, issues e *pins*.
Cada bloco `## ISSUE-NN — título` vira o Issue `[AOTM-NN] título`; a linha de comentário logo abaixo define
`labels | milestone | pin`. Não renumere (o código `AOTM-NN` aparece em commits e docs).
Prioridades: **p0** bloqueia a certificação 0.1.19 · **p1** deve entrar na 0.1.20 · **p2** melhoria/investigação.
Contexto e evidência: [PROJECT-STATE.md](PROJECT-STATE.md), [HOMOLOGATION-0.1.19.md](HOMOLOGATION-0.1.19.md),
[RUNTIME-EVIDENCE-2026-10-03.md](RUNTIME-EVIDENCE-2026-10-03.md), [DECISIONS-AND-REJECTIONS.md](DECISIONS-AND-REJECTIONS.md).

## ISSUE-00 — Tracker: roadmap 0.1.19 → 0.1.20
<!-- labels: tracker, p0 | milestone: 0.1.19 — Homologação runtime | pin: yes -->

Painel mestre do projeto. **Build em uso:** `0.1.19-dev` *disabled-renderer quiescence*, commit `afef9d7`, SHA-256 `481d6f7b…986ea`.
Status: `development; runtime certification pending`.

### Ordem de trabalho

- [ ] AOTM-01 Fechar a homologação runtime da 0.1.19 (Gates A/B/C + A/B visual)
- [ ] AOTM-02 Defeito de bioma ao reativar o renderer
- [ ] AOTM-03 "Duplicate or excessive LOD transfer"
- [ ] AOTM-04 Atribuir os hitches longos
- [ ] AOTM-05 Re-auditar ABI contra as versões reais do pack
- [ ] AOTM-06 Folga de heap com renderer ativo
- [ ] AOTM-07 Revalidar companions de servidor
- [ ] AOTM-08 Retenção/arquivamento de logs por rodada
- [ ] AOTM-09 Desambiguar a identidade das builds 0.1.19
- [ ] AOTM-10 Teste dedicado Crucible/Java 21
- [ ] AOTM-11 Build reprodutível fora desta máquina
- [ ] AOTM-12 Licença e visibilidade do repositório/JAR
- [ ] AOTM-13 (condicional) IBO/batching guiado por `gpu_terrain`
- [ ] AOTM-14 Crash CoreTweaks × CubicChunks (terceiros)

### Regras do jogo

Contrato de fidelidade visual, evidência antes de alegação, ownership/lifecycle: ver `CLAUDE.md` e `docs/DECISIONS-AND-REJECTIONS.md`.
Não iniciar a 0.1.20 sem decidir o escopo **com dados** (§3 de DECISIONS-AND-REJECTIONS).

## ISSUE-01 — Homologação runtime da 0.1.19: Gates A/B/C e A/B visual
<!-- labels: homologation, needs-evidence, p0 | milestone: 0.1.19 — Homologação runtime | pin: yes -->

### Contexto

A 0.1.19 passou nos gates offline (core 335 + API 5, ABI Angelica 2.1.5 e 2.2.8, 45/45 shaders idênticos, identidade Java 8/21) e foi
verificada de novo em 2026-10-04. Em runtime real há evidência **boa para DISABLED**, **parcial para reativação** e **nenhuma para
renderer ATIVO sustentado**, GPU capture, comparação visual e servidor dedicado.

### Lacunas (matriz G5–G10 de PROJECT-STATE §4)

- **Gate A (DISABLED)**: repetir com comparação de `Clientside chunk ticking` (vanilla `latest.log`) contra baseline 0.1.17/0.1.18.
- **Gate B (reativação)**: ≥ 2 ciclos DISABLED→DEFAULT→DISABLED; medir hitch do 1º frame (`LOD_RENDER_WALL` máx 1,6 s no único ciclo existente) e o catch-up (`QUIESCENT_CATCHUP_SUBMITTED`).
- **Gate C (renderer ATIVO)**: `/miraidiag gpu 60` com o jogador parado e em deslocamento; ≥ 10 min de sessão.
- **A/B visual**: capturas/vídeo da mesma cena (0.1.17 ou 0.1.18 × 0.1.19).

### Critérios de aceite

- Logs arquivados em `evidence/runtime-<data>/` com `scripts/summarize-mirai-sessions.py` (sem dados sensíveis).
- `docs/HOMOLOGATION-0.1.19.md` atualizado com as linhas G5–G8 e o veredito; status só muda de `pending` se os gates fecharem.
- Roteiro: `docs/history/releases/0.1.19-dev/RUNTIME-TEST.md` e `HOMOLOGATION-0.1.19.md` §9.

## ISSUE-02 — AssertFailureException de bioma em `getPlainsBiomeWrapper` ao reativar o renderer
<!-- labels: bug, needs-evidence, p0 | milestone: 0.1.20 — Candidato | pin: yes -->

### Sintoma (log real 2026-10-03)

`AssertFailureException` em `WrapperFactory.getPlainsBiomeWrapper:163`, ≈ 1 s depois de o renderer voltar de DISABLED para DEFAULT, ao
receber dados de rede (`FullDataPointIdMap.deserialize` → `deserializeBiomeWrapperOrGetDefault`).

### Hipóteses (não confirmadas)

1. O wrapper/nível de mundo ainda não está pronto no instante da reativação (render state reconstruído uma vez — decisão D-11).
2. Registro de biomas estendidos do **EndlessIDs 1.7.4** (o pack usa IDs estendidos; ver `COMPATIBILITY-STACK.md` §4).
3. Condição de corrida entre catch-up/sync e o init do wrapper.

### Critérios de aceite

- Reproduzir (reativação imediata vs. com espera; com/sem EndlessIDs no cliente) e anexar o stack completo.
- Correção com teste que cubra "dados de rede antes de o wrapper estar pronto"; **sem** mascarar o assert genérico.
- Repetir Gate B (AOTM-01) sem a exceção.

## ISSUE-03 — `IllegalArgumentException: Duplicate or excessive LOD transfer`
<!-- labels: bug, needs-evidence, p1 | milestone: 0.1.20 — Candidato | pin: no -->

`FullDataPayloadReceiver.receiveChunk:93` — 2 ocorrências (1 por sessão), ambas com o renderer em DISABLED. A exceção é o *guard* do receiver
(o estado não é corrompido), mas indica retransmissão/duplicata do servidor ou do sync diferido.

### Critérios de aceite

- Identificar quem envia a duplicata (servidor Crucible? `SyncOnLoad` + sync remoto?) com um `/miraidiag trace` durante a ocorrência.
- Decidir: dedupe no cliente antes do receiver, correção no emissor, ou manter o guard (e então rebaixar o log para contador).
- Teste em `tests/transport` que cubra o caso.

## ISSUE-04 — Hitches longos (6,5 s e 19,7 s) sem atribuição
<!-- labels: performance, needs-evidence, p1 | milestone: 0.1.20 — Candidato | pin: no -->

`FRAME_INTERVAL` máx 6,5 s (sessão 1) e 19,7 s (sessão 2). Com os contadores Mirai quiescentes, a causa provável é carga de mundo/outro mod;
o log vanilla já registrou `Clientside chunk ticking` > 100 ms (até 467 ms). O Mirai **não** deve "consertar por cima" o que não for dele (R-15).

### Critérios de aceite

- Capturar um hitch com `/miraidiag trace` (subfases de `LOD_UPDATE_WALL` já presentes) + `/miraidiag mark` + log vanilla.
- Conclusão registrada: Mirai (qual métrica) ou terceiros (qual mod/thread) — com evidência.

## ISSUE-05 — ABI não auditada contra as versões reais do pack (Angelica 2.2.20, GTNHLib 0.11.51, Hodgepodge 2.7.189)
<!-- labels: compatibility, p1 | milestone: 0.1.20 — Candidato | pin: no -->

As auditorias `tests/angelicaabi` cobrem Angelica 2.1.5/2.2.8 e GTNHLib 0.11.41; o pack roda **Angelica 2.2.20, GTNHLib 0.11.51, Hodgepodge 2.7.189**.
Já houve `NoSuchMethodError` por ABI (JOML, 0.1.2) e por fastutil (0.1.1).

### Critérios de aceite

- Rodar `audit.py` contra os JARs exatos do pack (e controle negativo) e anexar o relatório em `evidence/`.
- Estender o conjunto de dependências suportadas na documentação (`COMPATIBILITY-STACK.md`).
- Se houver diferença de descritor, abrir issue específico com a classe/método.

## ISSUE-06 — Folga de heap com renderer ativo (pico 4,71 GiB de 6 GiB = 78 %)
<!-- labels: performance, needs-evidence, p2 | milestone: 0.1.20 — Candidato | pin: no -->

Na sessão longa (≈ 2 h, renderer DISABLED na maior parte): pico 4,71 GiB / 6 GiB, 899 GCs em ≈ 63 s (≈ 0,9 % do tempo). Governor ativo
(configured=2, effective=2). Sem evidência de OOM, mas **não há medição com renderer ATIVO**.

### Critérios de aceite

- Série temporal de heap/GC com renderer ATIVO (parado e em deslocamento) durante ≥ 20 min.
- Decisão fundamentada sobre workers/razão do governor — sem reduzir qualidade visual.

## ISSUE-07 — Revalidar companions de servidor e localizar o Logout-Lifecycle-Fix
<!-- labels: compatibility, server, p1 | milestone: 0.1.20 — Candidato | pin: no -->

- `Akashic-DH-EndlessIDs-Compat 1.0.1` foi validado contra Mirai 0.1.16/0.1.17; confirmar se a 0.1.19 ainda precisa dele.
- `Akashic-DH-Rejected-Logout-Guard 1.0.0` foi validado contra a build **diagnóstica** (`04b35d24…`); revalidar contra `481d6f7b…`.
- O `INSTALL.txt` do Logout-Guard exige manter `Akashic-DH-Logout-Lifecycle-Fix-1.0.1-serveronly.jar`, que **não foi encontrado** neste PC.

### Critérios de aceite

- Rodar `verify.ps1`/`TransformerVerification` contra o JAR 0.1.19 quiescence.
- Localizar (ou reconstruir) o Logout-Lifecycle-Fix e arquivá-lo em `companions/`.
- Teste vivo: reproduzir uma conexão rejeitada pelo FML e confirmar que o backend permanece online.

## ISSUE-08 — Retenção de logs (~20 MiB) e arquivamento por rodada
<!-- labels: infra, p1 | milestone: Infra e documentação | pin: no -->

O logger mantém 5 segmentos (~20 MiB); as rodadas 0.1.16–0.1.18 já foram sobrescritas (por isso não há evidência ATIVA para elas).

### Critérios de aceite

- Rotina documentada: após cada rodada, `scripts/collect-mirai-logs.ps1` → arquivo datado fora do pack + resumo sanitizado em `evidence/`.
- Avaliar opção de aumentar o teto de segmentos (sem custo em runtime) ou um modo "keep-all" opt-in.

## ISSUE-09 — Identidade ambígua: duas builds 0.1.19-dev com o mesmo nome de JAR
<!-- labels: release, p1 | milestone: 0.1.20 — Candidato | pin: no -->

Diagnóstica (`04b35d24…`, commit `69645ac`) e *quiescence* (`481d6f7b…`, `afef9d7`) compartilham
`Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar`. O Logout-Guard foi validado contra o primeiro; o pack roda o segundo.

### Critérios de aceite

- Congelar a 0.1.19 e usar **sufixo/versão distinta** na próxima build (`0.1.20-dev`), com `build_info`/`mcmod.info`/manifestos coerentes.
- `verify-mirai-jar.py` falha se o sufixo esperado não bater.

## ISSUE-10 — Teste dedicado Crucible/Java 21 com a 0.1.19 (Gate G9)
<!-- labels: homologation, server, needs-evidence, p1 | milestone: 0.1.19 — Homologação runtime | pin: no -->

Nenhum teste registrado do **servidor dedicado** com a 0.1.19 (as classes major 52 rodam em Java 21/RFB, mas isso não foi comprovado em runtime).

### Critérios de aceite

- Backend Crucible com o mesmo JAR; login/logout, conexão rejeitada, `SyncOnLoad`, geração distante OFF, ≥ 30 min.
- Logs do servidor arquivados e resumidos; companions (AOTM-07) incluídos no cenário.

## ISSUE-11 — Build reprodutível fora desta máquina
<!-- labels: infra, release, p2 | milestone: Infra e documentação | pin: no -->

A equivalência fonte ≡ binário foi comprovada por arquivo (1.423/1.423) e metadados, mas a build **não foi reproduzida byte a byte** em outra máquina
(`COMPILACAO.md`: "equivalência byte a byte … ainda não foi certificada").

### Critérios de aceite

- Executar `docs/BUILD-AND-RELEASE.md` em outra máquina/CI e comparar o SHA-256 (ou documentar as fontes de não determinismo).

## ISSUE-12 — Licença e visibilidade: repositório privado × distribuição do JAR
<!-- labels: docs, release, p2 | milestone: Infra e documentação | pin: no -->

O repositório nasce **privado**. Se virar público ou se o JAR for distribuído: manter LGPL-3.0 (fontes junto do binário, avisos upstream), **sem código do Voxy**
(All rights reserved), e remover `docs/LOCAL-MACHINE-MAP.md` (caminhos locais). Ver `docs/LICENSING-AND-ATTRIBUTION.md`.

### Critérios de aceite

- Checklist de publicação executado (avisos, LICENSE, fontes, ausência de caminhos/logs sensíveis).

## ISSUE-13 — (Condicional) IBO/batching guiado por `gpu_terrain`
<!-- labels: research, performance, blocked, p2 | milestone: 0.1.20 — Candidato | pin: no -->

O IBO compartilhado foi **revertido** (R-06): saída idêntica, GPU neutra, ganho CPU pequeno e dependente de haver vários VBOs por container.
Só reabrir se a telemetria `gpu_terrain` (captura GPU com renderer ativo — depende de AOTM-01) mostrar `max_vbos_per_container` relevante.
Qualquer proposta exige prova de equivalência e A/B no pack real. **Não iniciar antes do Gate C.**

## ISSUE-14 — Crash CoreTweaks `LightFixStare` × CubicChunks (terceiros)
<!-- labels: compatibility, external, p2 | milestone: Infra e documentação | pin: no -->

`ArrayIndexOutOfBoundsException: 16` em `Chunk.getEBS_CubicChunks` na pilha do tick do cliente (sem frame Akashic). A correção correta é no armazenamento do
fork **CubicChunks** (`Akashiic/CubicChunks1710`, branch Crucible) ou na integração da API de altura — **não** capturando a exceção no render do Mirai (R-09).
Item de rastreio: reproduzir com o fork instalado e registrar a coordenada exata.
