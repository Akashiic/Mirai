# Shaders do Angelica × Mirai 0.1.19 — diagnóstico e ponte (2026-10-26)

**Sintoma (dono):** com shaders do Angelica ligados e o Mirai/DH ativo, o terreno distante (LODs) não aparece com o shader.
**Status:** diagnosticado; contornado **fora do Mirai** pela AkashicThePatcher `1.1.0-beta.42-MiraiShaderBridge`
(repositório `Akashiic/AkashicThePatcher`, `versions/Akashic-ThePatcher-1.1.0-beta.42-MiraiShaderBridge/`). Nenhum byte do JAR
do Mirai muda. Correção definitiva no Mirai: ver §4 (escopo candidato da 0.1.20, ISSUE-15).

## 1. Como o Angelica liga o suporte a DH

O Angelica (Iris portado) tem suporte a Distant Horizons desde a **2.2.18** (`net.coderbot.iris.compat.dh`, PR #1553). Ele só liga
quando `Class.forName("com.seibel.distanthorizons.DistantHorizonsTweaker")` funciona — em `Iris.onRenderSystemInit` (adiar o
carregamento do shaderpack até o `DhApiAfterDhInitEvent`) e em `DHCompat.run` (registrar os handlers de eventos da API DH).
Com o suporte ligado e um shaderpack com programas `dh_terrain`/`dh_water`, o Iris desenha os LODs com os programas do pack, nos
gbuffers do pack, e pede ao DH para adiar o passe translúcido (`setDeferTransparentRendering(true)`).

## 2. Por que não funcionava (três causas, provadas)

| # | Causa | Evidência |
|---|---|---|
| 1 | O DH upstream renomeou o coremod para `com.seibel.distanthorizons.DistantHorizonsTweaker` em 2026-09-10 (`dfca6541`, "Rename tweaker for Angelica compat"). O pin do Mirai (`merge-1.7.10` @ `b4abe2f`) é anterior; o Mirai ainda usa `com.seibel.distanthorizons.forge17.DistantHorizonsLoadingPlugin`. O Angelica acha que não há DH. | `git merge-base --is-ancestor dfca6541 b4abe2f` → não; JAR 0.1.19 (`481d6f7b…`) sem a classe; cliente real: `irisDhPresent=false` |
| 2 | `MixinRenderGlobal.renderDeferredLodsDuringTranslucentSetup` está comentado ("TODO Fix") no Mirai; o upstream reativou em 2026-09-10 (`ef720c8e`). Com o adiamento pedido pelo Iris, ninguém desenharia os LODs translúcidos (água distante). | `forge17/.../mixin/MixinRenderGlobal.java` |
| 3 | **Bug do Angelica no Java 8** (não do Mirai): o `ShaderPack` acha as pastas `world0/ world-1/ world1/` de packs sem `dimension.properties` com `Path::getFileName → Path::toString → matches("world-?\\d+")`; o zipfs do Java 8 devolve `world0/` (com barra) e nada casa. O pack roda Java 8 (`jre-legacy`): a BSL 8.2.09 (`.zip`) carregava **sem nenhum programa** e o Iris dizia "No DH shader found in this pack". A Complementary r5.9.3 tem `dimension.properties` e não é afetada. Ainda presente no `master` do Angelica em 2026-10-26. | cliente real beta.41: `folders=[]`, `gbuffers_terrain`/`dh_terrain` sem fonte |

Compatibilidade de API conferida: as 82 referências da Angelica 2.2.18/2.2.23/2.2.37 à API DH resolvem no JAR real da 0.1.19, e o
core do Mirai dispara todos os eventos que o Angelica registra (`AfterDhInit`, `BeforeRender`, `BeforeRenderPass`,
`BeforeBufferRender`, `BeforeTextureClear`, `BeforeApplyShader`, `BeforeRenderSetup/Cleanup`, `BeforeDeferredRender`,
`ColorDepthTextureCreated`, `BeforeGenericRenderSetup/ObjectRender`) e respeita os overrides `IDhApiFramebuffer`,
`IDhApiGenericObjectShaderProgram` e `IDhApiShadowCullingFrustum`. O `DhApiAfterDhInitEvent` é "one-time" e dispara no
*client started* qualquer que seja o `rendererMode` (inclusive `DISABLED`), então adiar o shaderpack não o deixa sem carregar.

## 3. A ponte (AkashicThePatcher beta.42, client-side)

* `MiraiShaderBridgeTransformer` (`@MixinSafeTransformer`): só arma com o Mirai instalado **e sem** `DistantHorizonsTweaker` (um Mirai
  rebaseado no upstream desliga a ponte sozinho). Troca o nome sondado em `Iris`/`DHCompat` pelo coremod do Mirai e acrescenta ao
  `MixinRenderGlobal` do Mirai o `@Inject` do upstream (após `EntityRenderer.enableLightmap` no passe 1), que chama
  `RenderHelper.renderDeferredLods()` **só enquanto o DH estiver adiando os translúcidos** — sem shader, nada muda (o caminho
  `OPAQUE_AND_TRANSPARENT` e a telemetria `CLIENT_DEFERRED_PASS_WALL` ficam como estão). Kill-switch `-Dakashic.miraiShaderBridge.disable=true`.
* `AngelicaShaderPackFolderTransformer`: o `Path::toString` daquele stream vira um hook que tira a barra final (identidade no Java 9+).
  Kill-switch `-Dakashic.angelicaShaderPackFolders.disable=true`.

Contrato de fidelidade visual do Mirai: com shader ativo quem define o visual dos LODs é o shaderpack (comportamento do
upstream: o Iris desliga SSAO/fog do DH via API enquanto o override está ativo e devolve ao sair). Sem shader, nenhum caminho do
Mirai muda.

## 4. Evidência de cliente real (headless, Linux, Mesa llvmpipe, Java 8u504)

Forge 1614 + UniMixins 0.3.1 + GTNHLib 0.11.51 + Angelica 2.2.37 + **Mirai 0.1.19 (`481d6f7b…`)**, todos tirados do TEOF-4.0, mais uma
mod-sonda (fonte em `AkashicThePatcher/versions/*beta.42*/tools/shader-probe/`). Relatório completo:
`AkashicThePatcher/versions/*beta.42*/reports/real-client/`.

| Rodada (300 s de mundo) | Iris vê DH | pastas do pack | `dh_terrain` | override | passes do DH | buffers no passe translúcido |
|---|---|---|---|---|---|---|
| beta.41 + BSL `.zip` | **não** | `[]` | ausente | — | só `OPAQUE_AND_TRANSPARENT` (4670) | 0 |
| beta.42 + BSL `.zip` | sim | `world0, world1, world-1` | presente | `shouldOverride=true` | `OPAQUE` 851 + `TRANSPARENT` 851 | 69727 |
| beta.42 sem shader | (sim) | — | — | — | só `OPAQUE_AND_TRANSPARENT` (4164), igual à beta.41 | 0 |
| beta.41 + Complementary r5.9.3 `.zip` | **não** | `world0, world1, world-1` (tem `dimension.properties`) | presente | — (Iris sem instância DH) | só `OPAQUE_AND_TRANSPARENT` (1880) | 0 |
| beta.42 + Complementary r5.9.3 `.zip` | sim | `world0, world1, world-1` | presente | `shouldOverride=true` | `OPAQUE` 605 + `TRANSPARENT` 605 | 45203 |

Contagens no último registro da sonda de cada rodada (os totais variam com o FPS do llvmpipe; o que importa é o tipo de passe).
Com a beta.42 e shader, o log mostra `[AngelicaShaders]: DH Ready, binding Iris event handlers...`, `DH Iris events bound.` e
`... first deferred translucent LOD pass`; nenhuma rodada da beta.42 imprimiu "No DH shader found" nem gerou crash report.

Não coberto: GPU real/Windows, o pack completo de 212 mods, sombras do DH (`dh_shadow`), desempenho (llvmpipe não serve para FPS).

## 5. Correção definitiva no Mirai (0.1.20, ISSUE-15)

1. Portar `dfca6541` (coremod `com.seibel.distanthorizons.DistantHorizonsTweaker`; atualizar `dh-loader.gradle`, `verify-mirai-jar.py`
   e a regra de identidade do manifesto) e `ef720c8e` (reativar `renderDeferredLodsDuringTranslucentSetup`).
2. Com isso a ponte da ThePatcher desliga sozinha (`... is present, Angelica detects this Distant Horizons by itself`).
3. O bug do zip no Java 8 é do Angelica: reportar upstream; até lá a ThePatcher cobre.
4. Opcional (como no `GlDhMetaRenderer` do upstream `merge-1.7.10` @ `315a4ba3`): recriar a textura de profundidade **antes** de destruir a antiga, para o
   cache de id do Iris não ficar preso numa textura apagada após redimensionar a janela.
