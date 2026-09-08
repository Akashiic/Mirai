# Instalação e primeira rodada — Mirai 0.1.4-dev

Esta é uma candidata de homologação para **Minecraft 1.7.10 / Forge / LWJGL2**, com integração ao Crucible. Consulte o relatório final da entrega para o nome do artefato, SHA-256, commits e testes efetivamente executados. O plano completo está em `HOMOLOGACAO-0.1.4-dev.md`. O adendo de escopo S-05 acrescenta associação de dados/hashes por lote, confirmação real de escrita e recuperação seletiva; o aceite dessas implementações permanece condicionado ao relatório final.

## Onde colocar o JAR

| Ambiente | Instalação |
|---|---|
| Cliente do modpack Forbidden Series | Substituir o Mirai anterior pelo JAR 0.1.4-dev em `mods` |
| Crucible 1.7.10 que já executa Mirai | Instalar o **mesmo JAR e a mesma versão** em `mods`, durante uma parada normal |
| Lobby Paper e proxy | Não instalar este mod nesses softwares; esta entrega não inclui companion para esses ambientes |

O arquivo esperado é `Akashic-Horizons-of-The-Mirai-forge17-0.1.4-dev-1.7.10.jar`. Mantenha apenas um JAR Mirai/Distant Horizons ativo em cada instalação. O identificador interno continua sendo `distanthorizons`; a mudança de nome visual não exige outra identidade de mod.

1. Feche completamente o Minecraft. Pare normalmente o Crucible antes de substituir o mod no servidor.
2. Guarde o JAR anterior fora de `mods` para permitir retorno à versão de comparação. Confira o SHA-256 publicado para o arquivo novo.
3. Substitua o JAR nos dois lados. Preserve configurações, saves, resource packs e caches existentes.
4. Inicie com as mesmas versões de Angelica, Hodgepodge, GTNHLib, UniMixins e LWJGL2 usadas na rodada anterior. **Deixe shaderpacks desligados no primeiro teste.**
5. Entre pelo proxy no lobby e depois no Crucible. Registre o resultado antes de mudar qualquer outra opção.

Não usar reload de plugins ou substituição do JAR com o jogo aberto como forma de atualizar o mod. O JAR pertence a `mods`, não a `plugins`.

## Condições conhecidas do seu teste

Os logs recebidos mostram **Java 8u51 no cliente Windows**, LWJGL 2.9.1, Intel UHD com driver 27.20.100.9268 e **Java 21.0.12 no servidor Linux**. São runtimes separados. Esta instalação não exige trocar automaticamente o cliente para Java 21 nem mudar LWJGL2 para LWJGL3.

As referências observadas do pack são Angelica 2.2.8, Hodgepodge 2.7.189, GTNHLib 0.11.41 e UniMixins 0.3.1. O ZIP atual contém apenas o log; o modpack completo, seus JARs exatos e as configurações integrais não foram recebidos nele. Portanto, preservar essas referências ajuda a comparar a alteração sem alegar homologação integral do pack.

As screenshots anteriores mostram distância vanilla 32 e raio LOD 64. Repita esse cenário primeiro. Em seguida, faça uma comparação na **mesma posição** com vanilla 8, mantendo o restante igual; isso é um controle do teste, não uma configuração alterada automaticamente pelo mod. Anote a mudança para que os resultados sejam interpretáveis.

## O que verificar

A primeira janela planejada é de aproximadamente **10 minutos**: observe céu e horizonte parado, gire a câmera devagar, caminhe ou voe pelo trajeto já filmado e revisite a região. Compare uma área já conhecida pelo cache com uma área nova.

Na etapa seguinte, reserve aproximadamente **20 minutos** para proxy → Paper → Crucible → Paper, reconexão e retorno ao mesmo local. As dimensões com conflitos de registro não devem ser usadas como prova de aprovação dessa candidata. Depois dos testes anteriores passarem, planeje uma sessão de **60 minutos** com movimento e pausas identificados. Esses tempos são propostas de execução, não resultados já realizados.

Registre separadamente:

- Céu ou fundo substituído por branco, com e sem terreno distante na direção da câmera.
- Faixa artificial entre terreno vanilla e LOD onde já existem dados distantes conhecidos.
- Geometria ainda carregando durante movimento rápido e áreas para as quais nenhum LOD foi armazenado.
- Diferenças em `ForceGlass`, minérios AE2 ou texturas do resource pack, que têm avisos próprios nos logs anteriores.
- Comportamento após troca de servidor, reconexão e mudança da janela.

A candidata limita a transição pela cobertura de chunks recebidos, mas chunk recebido não garante que a malha vanilla já terminou de compilar. Viewport retrato, prevenção manual de sobreposição em 1,0 e voo rápido têm limites próprios a verificar. Não concluir que qualquer região vazia seja falha do driver ou corrupção do cache.

## Cache, servidor e geração

Não é necessário apagar `Distant_Horizons_server_data`, saves ou configurações para instalar esta candidata. A correção de checksum conserva o formato dos dados existentes; checksum de 32 bits não é assinatura criptográfica e divergência histórica não autoriza limpeza destrutiva.

A auditoria encontrou 21 coordenadas de índice sem coluna correspondente na cópia SQLite anexada. O RAR não traz os arquivos WAL/SHM, então essa cópia não garante as últimas escritas da sessão; não atribuímos a causa individual dessas ausências. Separadamente, um reprodutor demonstrou associação incorreta de lotes e confirmação de escrita inexistente na implementação anterior. O escopo S-05 exige corrigir esse contrato e recuperar somente índice derivado comprovadamente inconsistente, preservando os dados e saves.

A estratégia planejada pelo implementador é **recuperação pontual quando o chunk for recebido ou revisitado**: antes de pular um hash igual, conferir os dados persistidos em worker existente e permitir reconstrução se a cobertura estiver ausente, incompleta ou ilegível. O aceite dessa implementação deve constar do relatório final. O trabalho ocorre fora da thread render; não é varredura global nem recuperação instantânea de todas as regiões, e não exige habilitar geração distante dedicada. Para homologar esse caminho, revisite uma região afetada, espere o processamento normal e registre o antes/depois. Não use remoção global de caches para fabricar um resultado aparentemente limpo.

Quando o backend fornece chave estável de mundo, o Mirai utiliza o cache persistente correspondente. No lobby sem chave estável, utiliza cache temporário isolado: esses LODs não são reaproveitados automaticamente entre sessões. A identidade do endpoint do proxy sozinha não diferencia seus backends. Preserve as chaves existentes do Crucible nesta comparação; mudar uma chave muda a pasta selecionada.

**Mantenha a geração distante dedicada experimental desativada nesta rodada.** Os logs anteriores confirmam essa condição, mesmo que a interface cliente apresente opção de geração no servidor habilitada. A opção do cliente não prova que o servidor autorizou ou executou geração. O Mirai não pode desenhar dados que nunca recebeu ou armazenou. Não habilite automaticamente Hodgepodge `addSimulationDistance_WIP` como tentativa de corrigir o céu.

O bloqueio antigo do CastleOfStone foi superado: houve recarga e uma segunda conexão prolongada na evidência atual. Não há alteração de política desse plugin nesta entrega. Se surgir uma rejeição nova, envie a mensagem e o horário atuais, mantendo a distinção entre as duas rodadas.

## Como devolver a evidência

Envie o `latest.log` **completo da mesma execução**, os logs correspondentes do Crucible e, se ocorrer, o crash report. Acrescente vídeo ou screenshots com o momento do problema, as distâncias vanilla/LOD, resolução, shaderpack e resource pack ativos. Inclua o nome e SHA-256 do JAR instalado.

Se a imagem voltar a ficar inválida ou houver travamento, encerre normalmente quando possível, preserve esses arquivos e retorne ao JAR anterior nos dois lados para comparação. Não misture uma troca de driver, runtime, resource pack e distância com a primeira reprodução do defeito: cada mudança deve ter seu resultado identificável.

A homologação ampla permanece pendente para os IDs de dimensão **-19, -37, -34, -38, -31, -39 e -42**, que apresentam falhas de registro, e para os avisos de texturas/shaders de outros mods. Não alterar esses IDs automaticamente em mundos existentes. A aprovação do JAR candidato cobre somente os gates executados e descritos no relatório final.
