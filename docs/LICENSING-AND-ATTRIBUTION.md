# Licença, atribuição e visibilidade

> Documento informativo, não é aconselhamento jurídico. Decisões de licenciamento são do dono do projeto.

## 1. Situação

| Componente | Licença | Observação |
|---|---|---|
| Distant Horizons (upstream `merge-1.7.10` + core) | **LGPL-3.0** | `LICENSE.txt` e `LICENSE.LESSER.txt` preservados na raiz; `LICENSE-LOGOS.txt` cobre os logotipos do upstream |
| Modificações AOTM (core, `common`, `forge17`, testes, scripts) | **LGPL-3.0** (obra derivada) | Cabeçalhos `license_header.txt` mantidos |
| Voxy (Cortex / MCRcortex) | **All rights reserved** | **Nenhum** código, shader, textura ou asset foi incorporado; apenas princípios gerais foram estudados (`docs/VOXY-DH-0.1.8.md`) |
| Companions (`companions/`) | Código original do dono; interagem por bytecode com o JAR do Mirai e com o EndlessIDs (sem redistribuí-los) | Definir licença explícita antes de publicar |
| Evidências (`evidence/`, `docs/history/`) | Dados/relatórios do dono | Logs foram **sanitizados** (sem chat, credenciais, JVM args, endereço de servidor, nome de jogador/mundo) |

## 2. Obrigações ao distribuir o JAR (LGPL-3.0)

1. Entregar **as fontes correspondentes** junto ao binário (o ZIP `Akashic-Mirai-<ver>-sources.zip` já cumpre isso, com o core modificado incluso).
2. Preservar avisos de copyright/licença do upstream (`mcmod.info` credita os autores originais).
3. Permitir substituição/relink da biblioteca (o projeto é um mod Forge sem ofuscação proprietária).
4. Marcar claramente que é um **fork modificado**: nome público *Akashic: Horizons of The Mirai*; atualizador upstream **desativado**
   (nunca reativar — regra 7 do `CLAUDE.md`). Manter `modid=distanthorizons` é decisão técnica de compatibilidade (D-01), não alegação de ser o upstream.
5. Nunca instalar junto de outro JAR Distant Horizons.

## 3. Regras sobre o Voxy

* É proibido copiar código, shaders, estruturas de dados nomeadas ou assets do Voxy (inclusive o clone de referência local em
  `akashic-audit\upstream\voxy`, que **não** vai para este repositório).
* É permitido estudar a *ideia* (renderização de LOD por voxels com GPU culling/arenas) e escrever código **original** — e só após novo desenho + A/B (R-13).
* O README descreve o AOTM como backport da **proposta** (DH + Voxy), não como porte do Voxy.

## 4. Checklist antes de tornar o repositório público

- [ ] Remover `docs/LOCAL-MACHINE-MAP.md` e quaisquer caminhos `C:\Users\…` (`git grep -n "C:\\\\Users"`).
- [ ] Revisar `evidence/` e `docs/history/` por dados pessoais (logs podem listar mods instalados e posições de seção codificadas).
- [ ] Confirmar que nenhum JAR de terceiros (Angelica, GTNHLib, Hodgepodge, Forestry…) foi commitado.
- [ ] Definir licença dos `companions/`.
- [ ] Decidir se os JARs serão publicados nas Releases (fontes ao lado, LGPL) ou só as fontes.
- [ ] Rever o texto "not affiliated with the Distant Horizons team / Voxy" no README.
- [ ] Rodar um *secret scan* (`gitleaks`/`trufflehog`) no histórico completo (13 branches + tags).

## 5. Visibilidade atual

O repositório é criado **privado**. Os JARs nas Releases ficam acessíveis apenas a quem tiver acesso ao repositório.
