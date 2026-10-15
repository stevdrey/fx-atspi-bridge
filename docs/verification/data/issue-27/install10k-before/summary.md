# Memory diagnostics summary

Source: `<out>/install10k-before`. Values: median (min–max) over repetitions; MB = 1024 KB.

## Phase deltas (MB, after − before)

Snapshot cost: RSS added by the diagnostic snapshots taken inside the interval (the phase's own "before" snapshot and any mid-phase snapshot), recorded as `<checkpoint>SnapshotRssKb`; the RSS and Anonymous deltas include it. – = not recorded by this collection.

| Cell | Runs | Phase | Point | Heap | RSS | Anonymous | Pss_File | Snapshot cost |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | 5 | install | installChurnCheckpoint | – | 5.6 (-18.6–1253.5) | 5.6 (-18.6–1253.5) | 0.0 (-0.0–0.0) | 0.0 (0.0–0.0) |
| install n=10000 bridge on arena2 | 5 | install | installChurnAfterGc | 1.5 (-0.4–2.1) | 4.9 (-19.5–1253.7) | 4.9 (-19.5–1253.7) | 0.0 (-0.0–0.0) | 0.0 (0.0–0.0) |
| install n=10000 bridge on arena2 | 5 | install | installChurnAfterTrim | – | -253.4 (-281.2–1035.8) | -253.4 (-281.2–1035.8) | 0.0 (-0.0–0.0) | 0.0 (0.0–0.5) |
| install n=10000 bridge on nmt | 5 | install | installChurnCheckpoint | – | -35.7 (-60.3–2.7) | -35.7 (-60.3–2.7) | 0.0 (-0.0–0.0) | 0.1 (0.0–0.3) |
| install n=10000 bridge on nmt | 5 | install | installChurnAfterGc | 0.5 (-0.4–1.6) | -36.7 (-60.9–2.5) | -36.7 (-60.9–2.5) | 0.0 (-0.0–0.0) | 0.1 (0.0–0.3) |
| install n=10000 bridge on nmt | 5 | install | installChurnAfterTrim | – | -285.9 (-324.6–-261.8) | -285.9 (-324.6–-261.8) | 0.0 (-0.0–0.0) | 0.3 (0.1–0.5) |
| install n=10000 bridge on plain | 5 | install | installChurnCheckpoint | – | -35.8 (-59.1–15.7) | -35.8 (-59.1–15.7) | 0.0 (-0.0–0.0) | 0.1 (0.1–0.2) |
| install n=10000 bridge on plain | 5 | install | installChurnAfterGc | 0.4 (0.2–1.6) | -35.7 (-60.2–14.9) | -35.7 (-60.2–14.9) | 0.0 (-0.0–0.0) | 0.1 (0.1–0.2) |
| install n=10000 bridge on plain | 5 | install | installChurnAfterTrim | – | -302.9 (-333.0–-270.6) | -302.9 (-333.0–-270.6) | 0.0 (-0.0–0.0) | 0.4 (0.2–0.5) |

## Install churn timing

| Cell | Close p50 ms | Close max ms | Ready p50 ms | Ready max ms | Provider threads at checkpoint |
| --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | 77.6 (71.1–91.9) | 119.5 (91.6–136.5) | 400.4 (387.1–444.4) | 482.7 (441.0–715.4) | 1.0 (1.0–1.0) |
| install n=10000 bridge on nmt | 75.1 (67.5–83.5) | 110.9 (79.3–133.2) | 400.7 (383.6–420.3) | 512.5 (442.1–634.7) | 1.0 (1.0–1.0) |
| install n=10000 bridge on plain | 72.9 (68.0–84.1) | 94.9 (87.5–110.3) | 392.2 (378.2–420.0) | 454.4 (408.5–506.9) | 1.0 (1.0–1.0) |

## glibc malloc (MB) and arenas

| Cell | Checkpoint | Arenas | Held from OS | Free in allocator | In use | Direct mmap |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | afterWarmupGc | 2 (2–2) | 278.3 (250.4–301.6) | 122.8 (99.3–150.6) | 151.0 (150.5–184.0) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | afterIdleGc | 2 (2–2) | 281.7 (250.4–302.4) | 129.5 (98.1–150.3) | 152.2 (152.1–152.5) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | structuralBefore | 2 (2–2) | 281.7 (250.4–302.4) | 129.5 (98.0–150.3) | 152.2 (152.2–152.5) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | structuralCheckpoint | 2 (2–2) | 419.5 (355.4–438.3) | 264.3 (196.9–276.1) | 159.6 (155.2–166.6) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | structuralAfterGc | 2 (2–2) | 419.5 (355.4–438.3) | 264.3 (196.9–283.6) | 155.2 (154.7–162.1) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | installChurnBefore | 2 (2–2) | 419.5 (355.4–438.3) | 264.2 (196.9–283.5) | 155.2 (153.4–162.1) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | installChurnCheckpoint | 2 (2–2) | 450.5 (412.9–467.4) | 286.8 (254.0–294.3) | 162.9 (158.9–173.1) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | installChurnAfterGc | 2 (2–2) | 450.5 (412.9–467.4) | 293.1 (255.7–313.1) | 156.5 (154.3–166.4) | 27.2 (27.2–27.2) |
| install n=10000 bridge on arena2 | installChurnAfterTrim | 2 (2–2) | 448.6 (401.1–455.0) | 289.7 (243.5–298.0) | 157.0 (156.1–166.4) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | afterWarmupGc | 65 (64–67) | 431.9 (398.7–457.8) | 264.3 (239.3–305.2) | 152.5 (150.8–192.6) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | afterIdleGc | 65 (64–67) | 433.7 (399.6–464.1) | 281.5 (247.5–311.9) | 152.2 (152.1–152.4) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | structuralBefore | 65 (64–67) | 434.1 (399.8–464.5) | 284.8 (250.7–317.3) | 149.3 (147.2–152.5) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | structuralCheckpoint | 74 (69–76) | 784.5 (615.1–861.3) | 625.0 (450.5–702.5) | 158.8 (156.9–164.6) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | structuralAfterGc | 74 (69–76) | 784.5 (615.3–861.5) | 628.0 (453.6–705.4) | 156.1 (154.0–161.7) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | installChurnBefore | 74 (69–76) | 784.5 (615.4–861.6) | 629.8 (460.6–713.3) | 154.8 (146.5–155.2) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | installChurnCheckpoint | 74 (69–76) | 796.1 (630.6–942.4) | 622.5 (457.2–782.1) | 168.6 (160.3–173.6) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | installChurnAfterGc | 74 (69–76) | 796.1 (630.7–942.4) | 640.8 (474.3–790.2) | 152.2 (150.1–156.5) | 27.2 (27.2–27.2) |
| install n=10000 bridge on plain | installChurnAfterTrim | 74 (69–76) | 796.1 (630.9–942.3) | 640.4 (474.0–790.1) | 152.2 (151.5–156.9) | 27.2 (27.2–27.2) |

## NMT committed (MB): phase deltas per category

Only categories whose median delta is at least 1 MB in some cell, plus Total and malloc.

| Cell | Point | Total | malloc | Arena Chunk | Code | Compiler | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on nmt | installChurnCheckpoint | -62.4 (-79.0–4.5) | 11.3 (3.7–19.3) | 2.4 (0.9–9.2) | 13.7 (1.1–15.2) | 0.0 (-0.0–0.1) | -1.6 (-2.1–0.0) | -80.0 (-104.0–0.0) | 1.6 (0.7–1.7) |
| install n=10000 bridge on nmt | installChurnAfterGc | -75.2 (-97.4–3.5) | 0.6 (-2.1–11.2) | 0.6 (-2.0–7.6) | 3.6 (0.6–8.0) | 1.8 (1.0–1.8) | -1.8 (-2.2–0.0) | -80.0 (-104.0–0.0) | 0.7 (0.4–0.8) |
| install n=10000 bridge on nmt | installChurnAfterTrim | -75.0 (-97.1–3.5) | 0.9 (-1.8–11.2) | 2.2 (-0.1–9.1) | 3.6 (0.6–8.0) | 0.1 (0.0–0.1) | -1.8 (-2.2–0.0) | -80.0 (-104.0–0.0) | 0.7 (0.4–0.8) |

Absolute committed at every checkpoint (MB):

| Cell | Checkpoint | Total | malloc | Arena Chunk | Code | Compiler | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on nmt | afterWarmupGc | 897.4 (786.4–953.4) | 39.9 (39.7–40.6) | 0.7 (0.5–1.3) | 23.6 (23.4–24.3) | 0.3 (0.3–0.3) | 75.6 (73.3–76.8) | 756.0 (648.0–810.0) | 14.3 (14.3–14.4) |
| install n=10000 bridge on nmt | afterIdleGc | 898.9 (788.2–954.5) | 41.3 (41.1–41.5) | 1.6 (0.9–1.7) | 23.8 (23.7–24.6) | 0.4 (0.3–1.1) | 75.6 (73.3–76.8) | 756.0 (648.0–810.0) | 14.4 (14.4–14.6) |
| install n=10000 bridge on nmt | structuralBefore | 897.9 (788.2–953.4) | 41.1 (38.3–41.3) | 1.6 (1.6–1.8) | 23.8 (21.5–23.9) | 0.3 (0.3–0.4) | 75.5 (73.3–76.8) | 756.0 (648.0–810.0) | 14.4 (14.4–14.6) |
| install n=10000 bridge on nmt | structuralCheckpoint | 886.9 (793.6–943.1) | 44.9 (43.4–46.4) | 1.8 (0.0–1.8) | 28.4 (26.2–29.2) | 0.3 (0.3–0.4) | 75.3 (73.4–76.5) | 740.0 (648.0–792.0) | 16.0 (15.9–16.0) |
| install n=10000 bridge on nmt | structuralAfterGc | 884.0 (792.3–939.0) | 42.1 (40.5–42.5) | 0.5 (0.1–1.8) | 24.9 (24.7–26.1) | 0.4 (0.3–0.7) | 75.3 (73.5–76.4) | 740.0 (648.0–792.0) | 16.0 (15.9–16.0) |
| install n=10000 bridge on nmt | installChurnBefore | 884.2 (792.3–939.0) | 40.7 (36.7–42.5) | 0.5 (0.3–2.0) | 24.8 (20.1–26.1) | 0.3 (0.3–0.3) | 75.3 (73.5–76.4) | 740.0 (648.0–792.0) | 16.0 (15.9–16.0) |
| install n=10000 bridge on nmt | installChurnCheckpoint | 823.7 (796.8–859.9) | 51.9 (46.2–60.1) | 2.7 (1.3–11.1) | 38.4 (27.2–40.3) | 0.3 (0.3–0.4) | 73.7 (73.5–74.4) | 662.0 (648.0–694.0) | 17.6 (16.7–17.6) |
| install n=10000 bridge on nmt | installChurnAfterGc | 810.5 (795.8–847.5) | 42.8 (38.5–47.9) | 0.9 (0.0–9.4) | 27.6 (23.7–32.8) | 2.1 (1.2–2.1) | 73.5 (73.3–74.2) | 662.0 (648.0–694.0) | 16.7 (16.4–16.8) |
| install n=10000 bridge on nmt | installChurnAfterTrim | 810.5 (795.8–847.5) | 43.1 (38.8–48.0) | 2.7 (1.9–11.0) | 27.6 (23.7–32.8) | 0.4 (0.3–0.4) | 73.5 (73.3–74.2) | 662.0 (648.0–694.0) | 16.7 (16.4–16.8) |

## Retained heap by group after GC (class histogram)

| Cell | Checkpoint | Bridge MB | Bridge objects | JavaFX MB | JavaFX objects | Other MB | TextField | Button | Scene | VBox |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | afterWarmupGc | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.23 (101.23–101.23) | 3369374 (3369324–3369406) | 68.97 (68.89–69.05) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | afterIdleGc | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.28 (101.28–101.28) | 3371007 (3370975–3371090) | 69.02 (68.94–69.09) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | structuralBefore | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.28 (101.28–101.28) | 3371007 (3370975–3371090) | 69.02 (68.93–69.09) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | structuralAfterGc | 1.30 (1.30–1.56) | 40054 (40054–45835) | 101.30 (101.30–101.30) | 3371491 (3371459–3371774) | 69.07 (68.64–69.94) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | installChurnBefore | 1.30 (1.30–1.31) | 40054 (40054–40360) | 101.30 (101.30–101.30) | 3371491 (3371459–3371774) | 69.07 (68.64–69.78) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | installChurnAfterGc | 1.30 (1.30–1.67) | 40063 (40063–48058) | 101.30 (101.30–101.31) | 3371491 (3371459–3371900) | 69.40 (69.01–70.49) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | afterWarmupGc | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.22 (101.22–101.23) | 3369248 (3369248–3369461) | 68.95 (68.65–68.95) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | afterIdleGc | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.28 (101.28–101.28) | 3370849 (3370849–3371062) | 68.98 (68.73–69.00) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | structuralBefore | 1.30 (1.30–1.70) | 40053 (40053–48926) | 101.28 (101.28–101.28) | 3370850 (3370849–3371063) | 68.98 (68.79–69.37) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | structuralAfterGc | 1.55 (1.30–1.76) | 45475 (40054–50057) | 101.30 (101.29–101.30) | 3371443 (3371402–3371554) | 69.90 (69.25–70.33) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | installChurnBefore | 1.30 (1.30–1.58) | 40190 (40054–46199) | 101.30 (101.29–101.30) | 3371443 (3371402–3371554) | 69.90 (68.91–70.41) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | installChurnAfterGc | 1.30 (1.30–1.65) | 40063 (40063–47771) | 101.30 (101.29–101.30) | 3371443 (3371401–3371554) | 70.32 (69.28–70.79) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | afterWarmupGc | 1.30 (1.30–1.38) | 40053 (40053–41776) | 101.23 (101.22–101.23) | 3369353 (3369248–3369413) | 68.89 (68.65–69.02) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | afterIdleGc | 1.30 (1.30–1.30) | 40053 (40053–40053) | 101.28 (101.28–101.28) | 3370954 (3370849–3371014) | 68.94 (68.69–68.98) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | structuralBefore | 1.30 (1.30–1.48) | 40053 (40053–44040) | 101.28 (101.28–101.28) | 3370954 (3370849–3371014) | 68.98 (68.69–69.00) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | structuralAfterGc | 1.30 (1.30–1.52) | 40054 (40054–44851) | 101.30 (101.29–101.30) | 3371487 (3371393–3371676) | 69.91 (69.53–70.12) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | installChurnBefore | 1.54 (1.30–1.71) | 45377 (40054–49058) | 101.30 (101.29–101.30) | 3371487 (3371393–3371677) | 70.08 (69.57–70.28) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | installChurnAfterGc | 1.44 (1.30–1.73) | 43211 (40063–49464) | 101.30 (101.29–101.30) | 3371487 (3371393–3371677) | 70.26 (69.87–70.60) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |

## Largest retained-heap growth per class (bridge on, plain; after GC − before)


install n=10000 bridge on plain:

| Class | Module | Δ instances | Δ KB |
| --- | --- | ---: | ---: |
| `[Ljava.util.HashMap$Node;` | java.base | 2 (1–2) | 128.0 (96.0–160.0) |
| `[Ljava.lang.Object;` | java.base | 18 (16–18) | 120.7 (108.5–159.3) |
| `[B` | java.base | 198 (172–248) | 10.2 (9.1–12.6) |
| `java.lang.Class` | java.base | 39 (39–39) | 4.7 (4.7–4.7) |
| `java.lang.String` | java.base | 198 (172–248) | 4.6 (4.0–5.8) |
| `java.lang.invoke.MemberName` | java.base | 61 (61–61) | 2.4 (2.4–2.4) |
| `dev.desktopcontrol.fxlab.BenchApp$Generation` | dev.desktopcontrol.fxlab | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.CountDownLatch$Sync` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `[D` | java.base | 2 (2–2) | 1.6 (1.6–1.6) |
| `java.util.concurrent.CountDownLatch` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.util.concurrent.atomic.AtomicInteger` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.lang.invoke.BoundMethodHandle$Species_LLLL` | java.base | 31 (31–31) | 1.5 (1.5–1.5) |
| `java.lang.invoke.ResolvedMethodName` | java.base | 46 (46–46) | 1.1 (1.1–1.1) |
| `jdk.internal.util.WeakReferenceKey` | java.base | 33 (32–33) | 1.0 (1.0–1.0) |

## pmap: anonymous versus file-backed RSS (MB)

Java heap: anonymous mappings inside the Java heap range. "recorded heap range" uses the reserved range the bench read from GC.heap_info; "legacy heuristic" (collections without it) counts every anonymous mapping below 0x100000000000, which with compressed oops is the heap plus class space. Outside Java heap: malloc arenas, thread stacks, class space and other native mappings.

| Cell | Checkpoint | Anonymous RSS | Java heap | Outside Java heap | File RSS | Anonymous mappings | Split |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| install n=10000 bridge on arena2 | afterWarmupGc | 785.9 (753.1–860.7) | 478.2 (467.2–554.4) | 306.3 (274.8–318.8) | 111.0 (110.6–111.2) | 191 (187–198) | recorded heap range |
| install n=10000 bridge on arena2 | afterIdleGc | 787.1 (754.2–862.6) | 479.2 (468.2–555.9) | 306.7 (275.1–319.0) | 111.0 (110.6–111.2) | 191 (187–198) | recorded heap range |
| install n=10000 bridge on arena2 | structuralBefore | 787.1 (754.3–862.6) | 479.2 (468.2–555.9) | 306.7 (275.1–319.0) | 111.0 (110.6–111.2) | 191 (187–198) | recorded heap range |
| install n=10000 bridge on arena2 | structuralCheckpoint | 1057.2 (1001.1–1199.8) | 625.0 (590.5–754.3) | 445.5 (376.1–454.0) | 114.7 (114.2–114.8) | 192 (185–200) | recorded heap range |
| install n=10000 bridge on arena2 | structuralAfterGc | 1057.2 (1001.1–1199.9) | 625.0 (590.5–754.4) | 445.5 (376.1–454.0) | 114.7 (114.2–114.8) | 192 (185–200) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnBefore | 1057.2 (1001.2–1199.9) | 625.0 (590.5–754.4) | 445.5 (376.1–454.0) | 114.7 (114.2–114.8) | 192 (185–200) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnCheckpoint | 1182.0 (1062.8–2254.7) | 680.7 (566.5–1781.4) | 501.3 (473.3–530.6) | 114.7 (114.2–114.8) | 192 (187–200) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnAfterGc | 1181.5 (1062.1–2254.9) | 680.7 (566.5–1781.4) | 500.8 (473.5–530.1) | 114.7 (114.2–114.8) | 202 (197–207) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnAfterTrim | 923.5 (804.3–2037.0) | 680.7 (566.5–1781.4) | 242.8 (237.9–266.2) | 114.7 (114.2–114.8) | 202 (197–207) | recorded heap range |
| install n=10000 bridge on plain | afterWarmupGc | 903.8 (893.2–947.3) | 555.5 (540.2–600.8) | 358.0 (337.7–361.0) | 110.8 (110.7–111.0) | 316 (308–317) | recorded heap range |
| install n=10000 bridge on plain | afterIdleGc | 963.8 (895.1–1002.7) | 599.6 (556.5–652.8) | 358.6 (338.6–364.3) | 110.8 (110.7–111.0) | 316 (308–317) | recorded heap range |
| install n=10000 bridge on plain | structuralBefore | 964.1 (895.5–1003.0) | 599.6 (556.5–652.8) | 359.7 (338.9–364.6) | 110.8 (110.7–111.0) | 316 (308–317) | recorded heap range |
| install n=10000 bridge on plain | structuralCheckpoint | 1320.4 (1206.9–1374.3) | 754.4 (703.6–758.0) | 586.8 (503.2–619.9) | 114.3 (114.2–114.5) | 336 (322–340) | recorded heap range |
| install n=10000 bridge on plain | structuralAfterGc | 1323.7 (1211.6–1376.3) | 756.2 (707.8–759.6) | 587.1 (503.8–620.0) | 114.3 (114.2–114.5) | 336 (322–340) | recorded heap range |
| install n=10000 bridge on plain | installChurnBefore | 1324.6 (1211.7–1376.4) | 756.2 (707.8–759.6) | 587.4 (503.9–620.1) | 114.3 (114.2–114.5) | 336 (322–340) | recorded heap range |
| install n=10000 bridge on plain | installChurnCheckpoint | 1287.7 (1177.1–1340.6) | 677.4 (645.0–698.4) | 607.4 (528.4–695.1) | 114.3 (114.2–114.5) | 336 (322–340) | recorded heap range |
| install n=10000 bridge on plain | installChurnAfterGc | 1286.8 (1176.5–1341.0) | 677.8 (645.1–699.4) | 606.6 (527.8–694.2) | 114.3 (114.2–114.5) | 348 (334–352) | recorded heap range |
| install n=10000 bridge on plain | installChurnAfterTrim | 1013.9 (941.0–1056.4) | 677.8 (645.1–699.4) | 333.6 (292.3–378.5) | 114.3 (114.2–114.5) | 348 (334–352) | recorded heap range |
