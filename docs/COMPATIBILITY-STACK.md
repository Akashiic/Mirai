# Pilha de compatibilidade (cliente, pacote e servidor)

O que se sabe — e o que **não** se sabe — sobre o AOTM convivendo com o resto do *forbidden-series*. Base: auditoria
`docs/COMPATIBILIDADE-0.1.8.md` (feita sobre a 0.1.7, fontes upstream congeladas por commit) + logs reais de 2026-10-03.
Regra de leitura: **presença simultânea de mods, aviso genérico de Forge ou relato upstream não é causalidade demonstrada.**

## 1. Versões reais em runtime vs. versões auditadas

| Componente | Em runtime (log 2026-10-03) | Auditado/testado offline | Situação |
|---|---|---|---|
| Angelica | **2.2.20** | 2.1.5 (compilação), 2.2.8 (ABI + fontes lidas) | **Lacuna**: ABI da 0.1.19 não auditada contra 2.2.20 |
| GTNHLib | **0.11.51** | 0.11.41 | **Lacuna** |
| Hodgepodge | **2.7.189** | — | Não auditado |
| CoreTweaks | 0.3.4.7-GTNH | 0.3.4.7 (fonte lida) | OK (leitura estática) |
| EntityCulling | backport 1.6.4-1.7.10 (`@VER@`) | tag `1.6.4-1.7.10` (fonte lida) | OK (leitura estática) |
| ParallelMipMap | 1.0 (coremod) | bytecode lido, sem fonte pública | Colisão potencial com Angelica no `TextureMap`; sem efeito provado |
| EndlessIDs | 1.7.4 | companion 1.0.1 vs. Mirai 0.1.16/0.1.17 | Revalidar contra 0.1.19 |
| CubicChunks | fork `v0.1.21-akashic-crucible-0.1.0-dev.N` (dev.4 na auditoria 0.1.7; dev.6 na rodada 0.1.9; dev.7 na 0.1.10; **versão atual não verificada**) | — | Crash CoreTweaks×CubicChunks conhecido (§3) |
| UniMixins / ArchaicFix / PlayerAPI / Backhand / ThePatcher | 0.3.1 / 0.8.0 / 1.5.0 / 1.8.13 / 1.1.0-beta.34 | — | Sem interação observada |
| Java cliente | 1.8.0_51 | 8u362 e 21.0.11 (testes/replay) | Quirk do C2 (§3) |
| Servidor | Crucible, Java 21 | companions offline | Sem teste dedicado da 0.1.19 |

**Ação aberta**: reexecutar `tests/angelicaabi/audit.py` com os JARs *exatos* do pack (Angelica 2.2.20, GTNHLib 0.11.51) — Issue "ABI vs runtime".

## 2. Contratos de integração que o código precisa manter

* **Angelica (GLSM)** transforma chamadas `org.lwjgl` no carregamento; o `LWJGL2Service` do Mirai passa por isso (inferência sustentada
  pelo código, não por dump de bytecode transformado). `GLStateManager.glGetInteger` devolve cache — **não** contar `glGetInteger` como stall.
* Mínimo Angelica **2.1.5**: profundidade via `IRenderTargetExt`; cor do fog lida por `FloatBuffer` (evita namespaces JOML incompatíveis —
  causa do `NoSuchMethodError` da 0.1.2). `GLState` restaura bindings/texturas/FBO/blend/depth/stencil/viewport/cull; `LegacyRenderState`
  cobre estado alterado fora do escopo principal. **Não simplificar esses guards globalmente.**
* **fastutil**: Mirai (8.2.1) × GTNHLib (8.5.18) colidiam (`NoSuchMethodError`, 0.1.1) → fastutil relocalizada de forma privada.
* **Projeção**: usar a matriz efetiva do jogo (CoreTweaks ajusta o far plane em `gluPerspective`); nunca reconstruir presumindo valores vanilla.
* **Workers**: o `WorldSlice`/`RenderThreadContext` do Angelica não torna seguros workers externos; manter snapshots do mundo no thread correto.
* **TextureMap**: evitar novos *overwrites* (ParallelMipMap substitui `loadTextureAtlas` inteiro; Angelica injeta no mesmo método). Invalidar só caches próprios no reload.

## 3. Defeitos de terceiros já identificados (não são do Mirai)

* **CoreTweaks `LightFixStare` × CubicChunks**: `ArrayIndexOutOfBoundsException: 16` em `Chunk.getEBS_CubicChunks` na pilha do tick do cliente,
  sem frame Akashic. Corrigir no armazenamento do fork CubicChunks; **não** mascarar no render do Mirai (R-09).
* **Angelica 2.2.8 issue #2038** (flicker em iGPU Intel com Indirect/CPU-only culling): relato com Java25/lwjgl3ify; hipótese a reproduzir, sem trocar opções automaticamente.
* **Forestry × C2 (Java 8u51)**: `forestry.core.genetics.Genome.getChromosomes` derruba o compilador C2. Contorno no launcher do pack:
  `-XX:CompileCommand=exclude,forestry/core/genetics/Genome.getChromosomes` (documentado desde 0.1.7; o Mirai não injeta).

## 4. EndlessIDs (biomas estendidos)

O EndlessIDs troca o `byte[256]` de biomas por armazenamento estendido e **lança exceção** se outro mod chamar `Chunk.getBiomeArray()`.
Mirai 0.1.16/0.1.17 chamava a API vanilla só como validação em 3 pontos (`MixinChunkMapCommon#onChunkSave` ×1, `LegacyLoadedTerrainPreparation#scanBatch` ×2);
o processamento real usa `World.getBiomeGenForCoords` via `ChunkWrapper`. O companion `Akashic-DH-EndlessIDs-Compat` 1.0.1 (servidor) redireciona essas
3 chamadas para um marcador interno, falha de forma *fechada* (bytecode inesperado ⇒ não transforma) e é idempotente.
**Pendências**: (a) confirmar se a 0.1.19 ainda precisa do patch ou já é nativamente compatível; (b) investigar se o `AssertFailureException`
de bioma observado na reativação do renderer (`WrapperFactory.getPlainsBiomeWrapper:163`) tem relação com o registro de biomas estendidos.

## 5. Matriz de testes de compatibilidade sugerida (mesmo pack, mesma rota)

Parado / giro / caminhada / voo; transição opaco→translúcido; água; céu e nuvens; inventário; F3+T (reload de recursos); troca de dimensão e
desconexão; altura próxima de 256; com e sem shaderpack; DISABLED→DEFAULT→DISABLED (≥ 2 vezes). Registrar com `/miraidiag mark` entre cenas
e arquivar os logs. Qualquer alegação de FPS exige A/B com o mesmo mundo, câmera e configuração.
