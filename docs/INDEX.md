# Índice da documentação

Ordem de leitura para quem está chegando (humano ou agente): **`../CLAUDE.md` → `PROJECT-STATE.md` → Issues fixados no GitHub → o resto sob demanda.**
Quando um documento antigo contradisser `PROJECT-STATE.md`, vale o `PROJECT-STATE.md`.

## 1. Retomada e estado (escritos em 2026-10-04)

| Documento | Para quê |
|---|---|
| [PROJECT-STATE.md](PROJECT-STATE.md) | Estado atual, matriz de homologação G1–G10, problemas abertos, ambiente, riscos, próximos passos |
| [HOMOLOGATION-0.1.19.md](HOMOLOGATION-0.1.19.md) | Veredito, cadeia artefato↔fonte↔git↔testes, protocolo dos Gates A/B/C, comandos de reprodução |
| [RUNTIME-EVIDENCE-2026-10-03.md](RUNTIME-EVIDENCE-2026-10-03.md) | O que os logs reais da 0.1.19 mostram (e o que não mostram) |
| [TIMELINE.md](TIMELINE.md) | 0.1.0 → 0.1.19: versões, commits, hashes, testes, marcos de evidência |
| [DECISIONS-AND-REJECTIONS.md](DECISIONS-AND-REJECTIONS.md) | Decisões de contrato, ideias rejeitadas (com evidência) e escopo candidato da 0.1.20 |
| [ISSUES-BACKLOG.md](ISSUES-BACKLOG.md) | Fonte dos Issues do GitHub (AOTM-00 … AOTM-19) |
| [BUILD-AND-RELEASE.md](BUILD-AND-RELEASE.md) | Compilar, gates offline, artefatos, instalar para teste, publicar |
| [COMPATIBILITY-STACK.md](COMPATIBILITY-STACK.md) | Angelica/GTNHLib/EndlessIDs/CubicChunks… versões reais × auditadas |
| [SHADERS-ANGELICA-DH.md](SHADERS-ANGELICA-DH.md) | Por que os shaders do Angelica não recebiam os LODs (2026-10-26), a ponte na AkashicThePatcher beta.42 e o que portar na 0.1.20 |
| [RELATED-PROJECTS.md](RELATED-PROJECTS.md) | Projetos achados no PC e no GitHub |
| [LICENSING-AND-ATTRIBUTION.md](LICENSING-AND-ATTRIBUTION.md) | LGPL-3.0, regra "sem Voxy", checklist de publicação |
| [LOCAL-MACHINE-MAP.md](LOCAL-MACHINE-MAP.md) | Caminhos reais na máquina do dono (**remover se o repo ficar público**) |

## 2. Arquitetura e diagnóstico (originais, pt-BR)

| Documento | Tema |
|---|---|
| [ARQUITETURA-MIRAI.md](ARQUITETURA-MIRAI.md) | Contrato de arquitetura (posse de chunks, transporte, sessão/mundo, defaults) |
| [DIAGNOSTICO-MIRAI.md](DIAGNOSTICO-MIRAI.md) | `/miraidiag`, logs JSONL, métricas e semântica dos timers |
| [VOXY-DH-0.1.8.md](VOXY-DH-0.1.8.md) | Estudo Voxy × DH (ideias, sem código) |
| [COMPATIBILIDADE-0.1.8.md](COMPATIBILIDADE-0.1.8.md) | Auditoria de Angelica, CoreTweaks, ParallelMipMap, EntityCulling |
| [COMPILACAO.md](COMPILACAO.md) | Pipeline original de build e rastreabilidade |
| [CONTEXTO-RETOMADA-0.1.8.md](CONTEXTO-RETOMADA-0.1.8.md) | Handoff da retomada 0.1.8 |
| [PESQUISA-GPU-0.1.10.md](PESQUISA-GPU-0.1.10.md) | Pesquisa GPU: hipóteses aceitas e variantes rejeitadas |

## 3. Por versão

* `CHANGELOG-0.1.0-dev.md … CHANGELOG-0.1.18-dev.md` (a 0.1.19 está em `history/releases/0.1.19-dev/PATCH-REPORT.md`).
* `PLANO-E-DIAGNOSTICO-0.1.9.md … 0.1.18.md` e `PLANO-0.1.8-dev.md`: hipótese → medição → critério de aceite de cada rodada.
* `ANALISE-LOGS-0.1.8/0.1.9/0.1.10.md`, `PERFORMANCE-EVIDENCE-0.1.7.md`, `PARECER-*.md`, `HOMOLOGACAO-0.1.4/0.1.5-dev.md`, `INSTALACAO-*.md`, `MIRAI-0.1.5-LOADED-PREPARATION.md`: histórico.
* `history/releases/<versão>/`: relatórios de entrega, `SHA256SUMS`, manifestos, contratos de JAR, relatórios de ABI.
* `history/audit-0.1.7/`: inventários da auditoria independente.

## 4. Fora de `docs/`

| Caminho | Conteúdo |
|---|---|
| `../README.md` / `../LEIAME.md` | Visão geral (EN / pt-BR) |
| `../CLAUDE.md` | Contrato de trabalho para agentes |
| `../MIRAI-UPSTREAM.json` | Pin do upstream e hashes de todas as releases conhecidas |
| `../evidence/` | Resumos sanitizados de runtime e verificação de artefato/testes |
| `../scripts/` | Verificador de JAR, replay Java 8/21, coleta/resumo de logs, gerador de Issues |
| `../companions/` | Patches de servidor (EndlessIDs-Compat, Rejected-Logout-Guard) |
| `../tests/`, `../testScripts/` | Harnesses headless (ABI, fog, fastutil, storage, transporte…) |

Os arquivos originais do fork upstream (`README-UPSTREAM.md`, `contributing.md`, `code_of_conduct.md`, `translations.md`, `.gitlab*`) foram mantidos por atribuição.
