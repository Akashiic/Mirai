# Mapa da máquina do dono (Windows 10, usuário `User`)

> **REMOVER ESTE ARQUIVO antes de tornar o repositório público** (contém caminhos locais). Ver `LICENSING-AND-ATTRIBUTION.md` §4.
> Snapshot de 2026-10-04. Útil para o Claude Code retomar o trabalho na mesma máquina sem refazer a busca.

## 1. Código e entregas do AOTM

Raiz: `C:\Users\User\Desktop\Codex\`

| Caminho | Conteúdo | Estado |
|---|---|---|
| `akashic-mirai\` | Repositório de trabalho original (histórico 0.1.7→0.1.19 + worktrees) | Origem deste repo (1.423/1.423 arquivos conferidos) |
| `akashic-mirai-disabled-quiescence-lab\` | Worktree `afef9d7` (0.1.19 quiescence); `forge17\build\libs\…0.1.19-dev-1.7.10.jar` = JAR em uso | Fonte de verdade da build atual |
| `akashic-mirai-perf-lab\` | Worktree `69645ac` (0.1.19 diagnostic) | — |
| `akashic-mirai-gpt-0.1.17\`, `akashic-mirai-gpt-0.1.18\` | Worktrees dos checkpoints | — |
| `akashic-mirai-gpu-lab\` | Experimentos GPU rejeitados | Só evidência |
| `Akashic-Mirai-0.1.17-dev-release\`, `Akashic-Mirai-0.1.18-dev-release\`, `Akashic-Mirai-0.1.19-dev-diagnostic\`, `Akashic-Mirai-0.1.19-dev-disabled-quiescence\` | Pastas de entrega (JAR + fontes ZIP + manifestos) | Binários vão para as GitHub Releases |
| `Akashic-Mirai-0.1.16-dev-release.zip`, `Akashic-Mirai-0.1.17-dev-release.zip` | Entregas arquivadas (≈ 68 MB cada) | Só local |
| `archive-2026-09-16T154758-0300.tar.gz` | Arquivo grande (≈ 640 MB) de 16/09/2026 | **Não analisado** (conteúdo desconhecido) |
| `akashic-audit\`, `akashic-audit-0.1.9\`, `-0.1.10\`, `-0.1.11\` | Auditorias + `upstream\distant-horizons` e `upstream\voxy` (referências) | **Não publicar `upstream\voxy`** (licença) |
| `entregas\` | 0.1.8–0.1.11 e World Firewall | Relatórios copiados p/ `docs/history/releases/` |
| `Akashic-DH-EndlessIDs-Compat\`, `Akashic-DH-Rejected-Logout-Guard\` | Companions de servidor | Copiados p/ `companions/` |
| **`Akashic-Horizons-of-The-Mirai-GitHub\`** | **Este repositório (staging → GitHub)** | Ativo |

## 2. Pack em uso (cliente)

| Caminho | Uso |
|---|---|
| `C:\Users\User\AppData\Roaming\.technic\modpacks\forbidden-series\mods\` | JAR AOTM instalado (SHA-256 `481d6f7b…986ea`); **não** mexer sem pedido; nunca dois JARs Mirai |
| `…\forbidden-series\config\DistantHorizons.toml` | Config efetiva (`rendererMode = "DISABLED"` nas rodadas de 2026-10-03) |
| `…\forbidden-series\logs\akashic-mirai\` | Logs JSONL schema 2 (5 segmentos ≈ 20 MiB; `latest.log` + rotações). Arquivar após cada rodada |
| `…\forbidden-series\logs\latest.log` (vanilla/FML) | Contém `Clientside chunk ticking took … ms` (usado nos gates) |

## 3. Toolchains

| Item | Caminho |
|---|---|
| Java 8 (Gradle toolchain) | `C:\Users\User\.gradle\jdks\temurin-8-amd64-windows\jdk8u362-b09` |
| Java 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` |
| JDK 25 (daemon do Gradle) | descoberto/baixado pelo wrapper (`gradle-daemon-jvm.properties`) |
| GitHub CLI | `gh`, conta `Akashiic` (escopos `repo`, `read:org`, `gist`) |

## 4. Temporários desta sessão (descartáveis)

`C:\Users\User\AppData\Local\Temp\aotm\` — scripts de análise de log, `verify-0.1.19\` (worktree destacado para os testes; **remover** com `git worktree remove`), `scan_related.txt`.

## 5. Repositório-irmão com o mesmo padrão de handoff

`C:\Users\User\Desktop\Codex\Akashic-Player-Backport-GitHub\` → `github.com/Akashiic/Akashic-Player-Backport` (privado): serviu de referência de formato
(README, `CLAUDE.md`, `docs/`) para este repositório.
