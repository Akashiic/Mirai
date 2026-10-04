# Akashic Mirai 0.1.19-dev — teste diagnóstico runtime

Objetivo: medir o que compõe LOD_UPDATE_WALL sem alterar qualidade visual.

1. Feche o Minecraft.
2. Substitua somente o JAR Akashic Mirai anterior pelo 0.1.19-dev desta pasta. Não deixe duas versões juntas.
3. Mantenha DistantHorizons.toml e o cache/banco atuais.
4. Entre no mesmo Overworld/cenário usado nos testes anteriores.
5. Confirme rendererMode=DEFAULT para a rodada com LODs visíveis.
6. Execute /miraidiag status.
7. Execute /miraidiag trace 60 e feche o chat.
8. Durante a trace: fique parado ~20 s; depois caminhe/voe ~20 s; depois volte a ficar parado ~20 s.
9. Em seguida execute /miraidiag gpu 60 e repita a mesma sequência com LODs visíveis.
10. Para validar o hard opt-out, mude rendererMode para DISABLED, rode /miraidiag trace 30 e aguarde sem trocar de mundo.
11. Reative DEFAULT e observe a recuperação/catch-up dos LODs.
12. Saia normalmente.

Enviar/analisar: logs/akashic-mirai/latest.log e previous-*.log da sessão, além do latest.log normal do Minecraft.

Métricas novas trace-only: LOD_UPDATE_LOCK_WAIT, EXISTS_CHECK, LOAD, MERGE, SKYLIGHT, ENCODE, DB_SAVE e NOTIFY.
O pai LOD_UPDATE_WALL inclui as subfases; não somar pai + filhos.
