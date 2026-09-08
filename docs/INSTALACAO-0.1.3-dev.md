# Instalação da Mirai 0.1.3-dev

1. Feche o Minecraft completamente e substitua o JAR Mirai anterior em `mods` por `Akashic-Horizons-of-The-Mirai-forge17-0.1.3-dev-1.7.10.jar`.
2. Mantenha somente um JAR Mirai/Distant Horizons. Preserve as versões atuais do pack neste teste: Angelica, GTNHLib, Hodgepodge, UniMixins e LWJGL2.
3. Entre pelo proxy no lobby Paper, confira imagem, movimentação e fog; passe para o Crucible e volte ao lobby. Repita uma desconexão/reconexão e uma troca de dimensão.
4. Se houver falha, preserve `latest.log` completo e o crash report desta execução, incluindo o nome e SHA-256 do JAR instalado.

**Mudança de comportamento:** num servidor que não fornece chave estável de mundo, os LODs passam a usar cache temporário isolado. Eles são reconstruídos a partir dos dados recebidos ao entrar novamente. Isso impede reaproveitar automaticamente dados de outro backend atrás do mesmo proxy. Caches antigos ficam preservados, sem fusão automática. A limpeza só percorre o diretório temporário criado por esta sessão, após os providers sinalizarem fechamento; falha de fechamento preserva esse diretório.

Servidores com chave estável continuam usando cache persistente. Para múltiplos backends com Mirai, configure valores estáveis e distintos conforme os contratos existentes `Config.Server.serverKey` e `Config.Server.levelKeyPrefix`, e confira os identificadores recebidos nos logs. Alterar essas chaves altera a pasta usada. A identidade do endpoint por si só não diferencia backends do mesmo proxy.

Nenhum JAR adicional é exigido no Paper para renderizar chunks recebidos pelo cliente. Esta entrega não inclui ponte Paper/Bukkit. Se o Mirai já estiver no Crucible, substitua o mod durante uma parada normal e mantenha a mesma versão nos dois lados. Não coloque este JAR em `plugins`.

O Java 21 do Crucible pertence ao servidor. O log anterior mostra Java 8u51 no cliente; esta build mantém bytecode Java 8 e LWJGL2. As execuções automatizadas da entrega usam Linux/Java 8u504 e Java 21, sem certificar o Windows/8u51 do pack.

Não é necessário apagar saves, configurações ou caches antigos. A versão continua **dev** até completar a matriz funcional do usuário, incluindo sessão prolongada, renderização real e recuperação de falhas em cópia de teste.
