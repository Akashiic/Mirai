# Resumo de sessoes Akashic Mirai (gerado)

Arquivos lidos: latest.log, previous-1.log, previous-2.log, previous-3.log, previous-4.log

## Sessao fee21e04

- Janela: 2026-10-03T21:45:31.548Z -> 2026-10-03T23:46:43.052Z (UTC); segmentos: previous-1.log, previous-2.log, previous-3.log, previous-4.log
- Identidade: {"env.java.version": "1.8.0_51", "env.java.vendor": "Oracle Corporation", "env.java.vm.name": "Java HotSpot(TM) 64-Bit Server VM", "env.os.arch": "amd64", "env.processors": "8", "version": "0.1.19-dev", "commit": "afef9d7e4f085c0d1dccba66d763db22878714ca", "branch": "akashic/mirai-0.1.19-disabled-quiescence", "forge": "10.13.4.1614"}
- Modo do renderer: [["2026-10-03T21:45:31.549Z", "DISABLED"], ["2026-10-03T23:11:46.659Z", "DEFAULT"], ["2026-10-03T23:12:16.825Z", "DISABLED"]]
- VBO Render Count (max): 59/147
- JVM: {"heap_used_peak": 5057691720, "heap_max": 6442450944, "gc_count_total": 899, "gc_ms_total": 63227, "system_ram_total": 17023455232, "live_threads": 72}
- Janelas com renderer ativo: {"windows": 6, "frames": 564, "mean_frame_interval_ms": 56.42, "mean_fps_equiv": 17.7, "worst_frame_interval_ms": 1770.5}
- Demais janelas: {"windows": 1437, "frames": 383819, "mean_frame_interval_ms": 18.85, "mean_fps_equiv": 53.1, "worst_frame_interval_ms": 6498.4}
- Fim de sessao: ["normal_shutdown; queued=0 dropped_total=0"]

| Timing (wall) | count | mean_us | max_us |
|---|---:|---:|---:|
| CLIENT_RENDER_PASS_WALL | 384217 | 56 | 1730302 |
| FRAME_EVENT_SPAN | 384384 | 12887 | 1766842 |
| FRAME_INTERVAL | 384383 | 18900 | 6498409 |
| GL_DATA_UPLOAD_WALL | 4 | 5235 | 17863 |
| GL_STORAGE_UPLOAD_WALL | 8 | 582 | 1295 |
| LOD_RENDER_LIST_WALL | 393 | 369 | 19089 |
| LOD_RENDER_WALL | 393 | 14834 | 1608654 |
| LOD_UPDATE_WALL | 760 | 9751 | 162571 |
| NETWORK_REQUEST_WALL | 247 | 82930 | 455568 |
| RENDER_TASK_QUEUE_WALL | 384217 | 0 | 47918 |
| RENDER_TASK_WALL | 449 | 437 | 47744 |

Contadores != 0: {"NETWORK_FRAGMENT_BYTES": 5083682, "GL_DATA_UPLOAD_BYTES": 786912, "GL_STORAGE_UPLOAD_BYTES": 248064, "QUIESCENT_CHUNK_DEFERRED": 8339, "DB_COMMITTED": 722, "QUIESCENT_PARTIAL_UPDATE_SUPPRESSED": 327, "COLD_REQUEST": 247, "NETWORK_SENT": 247, "NETWORK_RECEIVED": 247, "QUIESCENT_REMOTE_SYNC_SUPPRESSED": 90, "COLD_MISSING": 56, "QUIESCENT_CATCHUP_SUBMITTED": 39, "COLD_PRESERVED_LOCAL": 38, "CHUNK_SUBMITTED": 34, "DH_ERROR_REPORTS": 3, "errors_DH_LOG": 2, "NETWORK_CANCELLED": 1, "errors_NETWORK": 1}

- ERRO 2026-10-03T23:11:47.238Z (previous-2.log): `NETWORK cause=com.seibel.distanthorizons.core.util.LodUtil$AssertFailureException <- com.seibel.distanthorizons.common.wrappers.WrapperFactory.getPlainsBiomeWrapper:163 <- com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory.deserializeBiomeWrapperOrGetDefault:59 <- com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap.deserialize:309 <- com.seibel.distanthorizons.co`
- ERRO 2026-10-03T23:11:47.239Z (previous-2.log): `DH_LOG cause=com.seibel.distanthorizons.core.util.LodUtil$AssertFailureException <- com.seibel.distanthorizons.common.wrappers.WrapperFactory.getPlainsBiomeWrapper:163 <- com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory.deserializeBiomeWrapperOrGetDefault:59 <- com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap.deserialize:309 <- com.seibel.distanthorizons.cor`
- ERRO 2026-10-03T23:21:52.708Z (previous-2.log): `DH_LOG cause=java.lang.IllegalArgumentException <- com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayloadReceiver.receiveChunk:93 <- com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState$$Lambda$2378/1200857351.accept:-1 <- com.seibel.distanthorizons.core.network.event.AbstractNetworkEventSource.lambda$registerHandler$2:150 <- com.seibel.distanthorizons.core.network.e`

## Sessao 2f3a6ba1

- Janela: 2026-10-03T23:48:50.587Z -> 2026-10-04T00:11:30.851Z (UTC); segmentos: latest.log
- Identidade: {"env.java.version": "1.8.0_51", "env.java.vendor": "Oracle Corporation", "env.java.vm.name": "Java HotSpot(TM) 64-Bit Server VM", "env.os.arch": "amd64", "env.processors": "8", "version": "0.1.19-dev", "commit": "afef9d7e4f085c0d1dccba66d763db22878714ca", "branch": "akashic/mirai-0.1.19-disabled-quiescence", "forge": "10.13.4.1614"}
- Modo do renderer: [["2026-10-03T23:51:23.621Z", "DISABLED"]]
- VBO Render Count (max): 0/0
- JVM: {"heap_used_peak": 3825690536, "heap_max": 6442450944, "gc_count_total": 286, "gc_ms_total": 19026, "system_ram_total": 17023455232, "live_threads": 68}
- Janelas com renderer ativo: {"windows": 0, "frames": 0, "mean_frame_interval_ms": null, "mean_fps_equiv": null, "worst_frame_interval_ms": 0.0}
- Demais janelas: {"windows": 216, "frames": 56376, "mean_frame_interval_ms": 19.84, "mean_fps_equiv": 50.4, "worst_frame_interval_ms": 19749.9}
- Fim de sessao: ["normal_shutdown; queued=0 dropped_total=0"]

| Timing (wall) | count | mean_us | max_us |
|---|---:|---:|---:|
| CLIENT_RENDER_PASS_WALL | 56351 | 101 | 2029360 |
| FRAME_EVENT_SPAN | 56569 | 13153 | 9915509 |
| FRAME_INTERVAL | 56568 | 19854 | 19749943 |
| RENDER_TASK_QUEUE_WALL | 56351 | 1 | 56994 |
| RENDER_TASK_WALL | 1 | 52524 | 52524 |

Contadores != 0: {"NETWORK_FRAGMENT_BYTES": 1400612, "QUIESCENT_CHUNK_DEFERRED": 2457, "QUIESCENT_PARTIAL_UPDATE_SUPPRESSED": 128, "DH_ERROR_REPORTS": 2, "errors_DH_LOG": 1}

- ERRO 2026-10-04T00:00:49.053Z (latest.log): `DH_LOG cause=java.lang.IllegalArgumentException <- com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayloadReceiver.receiveChunk:93 <- com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState$$Lambda$2386/50205018.accept:-1 <- com.seibel.distanthorizons.core.network.event.AbstractNetworkEventSource.lambda$registerHandler$2:150 <- com.seibel.distanthorizons.core.network.eve`
