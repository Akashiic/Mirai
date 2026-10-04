# Build, verificação e release

Procedimento reprodutível para compilar, verificar e entregar uma build AOTM. Complementa `docs/COMPILACAO.md` (original, 0.1.7) e a
tabela de gates de [HOMOLOGATION-0.1.19.md](HOMOLOGATION-0.1.19.md). **Os gates offline são necessários, não suficientes**: nenhum
deles inicia Minecraft, Crucible ou OpenGL.

## 1. Pré-requisitos (Windows, máquina do dono)

| Item | Detalhe |
|---|---|
| Gradle | Wrapper **9.4.1** (SHA-256 fixado); daemon exige **JDK 25** (`gradle/gradle-daemon-jvm.properties`) |
| JDKs descobríveis | **Java 8** (`temurin-8 jdk8u362` em `~\.gradle\jdks`) e **Java 21** (`Eclipse Adoptium jdk-21.0.11.10`) para replay de testes e identidade |
| Python | ≥ 3.11 (`scripts/*.py`) |
| Alvo | **sempre** `"-PmcVer=1.7.10"` (aspas obrigatórias em PowerShell) |
| Rede | Dependências vêm dos repositórios configurados; checksums efetivamente resolvidos vão na evidência da entrega |

> **Armadilha**: em PowerShell, `-PmcVer=1.7.10` sem aspas é dividido no ponto e o Gradle compila o alvo padrão **1.20.1** sem avisar.
> Confirme que a saída lista as tarefas `:forge17:*` e que o JAR final tem `1.7.10` no nome.

## 2. Compilar

Use um **worktree limpo** no commit desejado para que `build/`, `build.properties` e outras sobras não vazem para a árvore de trabalho:

```powershell
git worktree add C:\Temp\aotm\verify <commit>            # detached
cd C:\Temp\aotm\verify
.\gradlew.bat "-PmcVer=1.7.10" --max-workers=4 --no-parallel build
```

Pipeline (tarefa `:forge17:shadeDowngradedApi`): `shadowJar → remapJar → downgradeJar → shadeDowngradedApi`.
**Só o resultado de `shadeDowngradedApi` é release**: `forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-<ver>-1.7.10.jar`.
Os JARs `-all`, `-dev` e `-sources` são intermediários.

Ao final: `git worktree remove --force C:\Temp\aotm\verify`.

## 3. Gates offline (ordem recomendada)

| # | Gate | Como | Critério |
|---|---|---|---|
| 1 | Testes core + API | `gradlew.bat "-PmcVer=1.7.10" --max-workers=4 --no-parallel :core:test :api:test` (~1 min 16 s a quente) | 335 + 5 na 0.1.19, 0 falhas/erros, 1 skip |
| 2 | Compilação dos módulos | `common:compileJava`, `forge17:compileJava` | PASS |
| 3 | Integridade do JAR | `zipfile.testzip`; majors das classes | Somente 46, 51 e 52 (Java 8) |
| 4 | Verificador | `python scripts/verify-mirai-jar.py <jar> --expected-version V --expected-commit SHA --expected-branch BR` | PASS (versão, `Git_Commit`, `Git_Branch`, `Build_Source` empacotados) |
| 5 | Identidade com classes reais | `tests/release/run_identity.py` em Java 8 e 21 | PASS |
| 6 | Replay Java 8/21 | `scripts/replay-mirai-tests.py` | Mesmos resultados nas duas JVMs |
| 7 | ABI | `python tests/angelicaabi/audit.py <jar> --dependency angelica-X.jar --dependency gtnhlib-Y.jar [--negative-control <jar antigo>] --report abi.json` | PASS; **rodar contra os JARs reais do pack** |
| 8 | Shaders | Comparar os recursos empacotados com a build anterior | 45/45 byte-idênticos (contrato visual) |
| 9 | Regressões auxiliares | `tests/fogcolor`, `tests/fastutilcollision`, `tests/storage`, `tests/transport`… (cada um com README) | PASS |
| 10 | Fonte ≡ binário | `git archive <commit>` vs ZIP de fontes entregue; `build_info.json` | 0 diferenças |
| 11 | Contrato de beacons (se tocado) | `scripts/verify-beacon-refresh-contract.py` | PASS |

`:api:verifyReleaseVersion` compara `ModInfo.VERSION` com `mod_version` antes de compilar. Mantenha **todas** as superfícies de identidade
sincronizadas: `gradle.properties mod_version`, `ModInfo.VERSION`, nome do JAR, `build_info.json`, `mcmod.info`, changelog,
`MIRAI-UPSTREAM.json`.

## 4. Artefatos de uma entrega

Por versão (`docs/history/releases/<ver>/` guarda a parte textual; os binários vão nas *GitHub Releases*):

* `Akashic-Horizons-of-The-Mirai-forge17-<ver>-1.7.10.jar` (≈ 45 MB);
* `Akashic-Mirai-<ver>-sources.zip` (core modificado incluso, **sem** jogo/caches/JARs privados);
* `SHA256SUMS.txt`, `RELEASE-MANIFEST.json`, `jar-contract-*.json`, `release-identity-*.json`, `verify-*.json`, `TEST-SUMMARY-*.json`,
  `RELATORIO-ENTREGA-*.md` / `PATCH-REPORT.md`, `RUNTIME-TEST.md` (roteiro de teste), relatórios ABI.

Regras de entrega: o ZIP de fontes acompanha todo binário (LGPL-3.0); o relatório separa **o que rodou com dependências reais, com
dublês e o que não rodou**; o status sai como `approved_for_controlled_runtime_test; stable_pending_runtime` até existir evidência runtime.

## 5. Instalar para teste (no pack do dono)

1. Fechar o launcher; **backup do JAR atual fora de `mods/`** (nunca deixar dois JARs Mirai/DH em `mods/`).
2. Copiar o JAR novo para `…\.technic\modpacks\forbidden-series\mods\`.
3. Preservar `config\DistantHorizons.toml`. Para o Gate A manter `rendererMode = "DISABLED"`.
4. Subir o jogo e conferir no log `logs\akashic-mirai\latest.log` os campos `identity.version/commit/branch`.
5. Rodar o roteiro `RUNTIME-TEST.md` da versão; arquivar logs com `scripts/collect-mirai-logs.ps1` e resumir com
   `scripts/summarize-mirai-sessions.py` (commitar **só** o resumo sanitizado).
6. Reverter: restaurar o JAR anterior, ou `-Dakashic.mirai.disabledRendererQuiescence=false` (e demais chaves, `CLAUDE.md` regra 12).

## 6. Servidor Crucible / Java 21

O JAR é cliente **e** servidor (Forge 1.7.10). No backend, o runtime é Java 21 com RFB; as classes major 52 rodam lá, mas **não há teste
dedicado registrado para a 0.1.19** (Gate G9). Companions server-side em `companions/` (ver [RELATED-PROJECTS.md](RELATED-PROJECTS.md)).
Nunca usar `/reload`; reiniciar o backend por completo ao trocar JARs.

## 7. Publicar no GitHub (padrão deste repositório)

* Uma tag anotada por versão (`v0.1.N-dev`; a 0.1.19 tem também `v0.1.19-dev-diagnostic`).
* Uma *pre-release* por tag, com o JAR, o ZIP de fontes e o `SHA256SUMS.txt` como assets. **Marque como pre-release**: nada foi certificado em runtime.
* Cuidado com o **nome de arquivo duplicado** das duas builds 0.1.19: nas Releases o asset diagnóstico é renomeado com sufixo `-diagnostic`.
  O SHA-256 do arquivo original não muda.
