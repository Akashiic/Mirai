# Mirai 0.1.4-dev — composição e cobertura recebida

Candidata de homologação baseada na 0.1.3-dev, orientada pelo vídeo, capturas, opções, logs e cache fornecidos em 5 de setembro de 2026. O relatório da entrega registra os testes executados e o hash do JAR aprovado.

| Achado | Alteração |
|---|---|
| R-01 | Composição do terreno distante e antialiasing consideram se há shaderpack efetivamente ativo. Angelica instalado com shaderpacks desligados deixa de suprimir a composição necessária para preservar o céu Minecraft. |
| R-02 | Fade GLSL usa limites crescentes em `smoothstep`. Pixels sem LOD retornam a cor Minecraft antes da reconstrução da posição de fundo. |
| R-03 | Entradas ausentes, viewport inválido, framebuffer incompleto e exceções de composição interrompem o passe antes da cópia final. A proteção existente do cliente recebe a falha; os recursos e o estado têm limpeza/restauração tentadas. |
| G-01 | No 1.7.10, a distância usada na transição vanilla/LOD é limitada pela cobertura contígua de chunks recebidos. A consulta é limitada, reutilizada por tick/chave e não gera chunks. O diagnóstico expõe as distâncias relevantes. |
| S-04 | A criação do DTO calcula o checksum dos dados atuais, incluindo alterações de iluminação realizadas depois de um hash anterior. Mantém formato e algoritmo, sem migração nem rejeição dos bancos existentes. |
| S-05 | Associa hashes ao lote de dados correspondente e exige confirmação de gravação antes de publicá-los. Falhas de executor, codificação ou SQL deixam de sinalizar sucesso. O fast path confere a cobertura persistida para permitir reprocessar seletivamente hashes antigos sem dados, usando o worker existente. |

O checksum é um metadado de 32 bits, não autenticação. Os bancos examinados passaram nas verificações estruturais, mas o arquivo enviado não continha os WAL correspondentes: a auditoria cobre o conteúdo consolidado, sem certificar todas as gravações recentes. Nenhum cache ou save precisa ser apagado para aplicar esta atualização.

A recuperação seletiva depende de receber ou revisitar os chunks correspondentes. Não é uma reconstrução global nem geração automática de terreno. Datasources, callbacks e consumidores de atualizações respeitam o término da gravação; falhas de notificação após commit são distinguidas de falhas de persistência.

A medição de cobertura identifica chunks recebidos; não comprova que todas as malhas vanilla já estejam prontas. Também não cria LODs para regiões ainda não armazenadas. Proporções de tela incomuns, overdraw manual e voo rápido permanecem na matriz visual.

Continuam preservados o backend LWJGL2, a identidade técnica `distanthorizons`, as bibliotecas privadas e os controles anteriores de rede, sessão, persistência e recursos. Geração distante dedicada experimental permanece desativada por padrão. Não há novo companion para o lobby Paper nem atualização obrigatória das dependências nesta candidata.

Consulte [instalação](INSTALACAO-0.1.4-dev.md) e [homologação](HOMOLOGACAO-0.1.4-dev.md). A aprovação da build para testes não certifica o modpack integral, todos os drivers ou as dimensões com conflitos de registro.
