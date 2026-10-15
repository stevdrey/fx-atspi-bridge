# Memory diagnostics summary

Source: `/tmp/claude-1000/-home-stevdrey-Projects-Java-fx-atspi-bridge/d0e83f72-2099-4404-b9ff-cb6778751045/scratchpad/data/install10k-after`. Values: median (min–max) over repetitions; MB = 1024 KB.

## Phase deltas (MB, after − before)

Snapshot cost: RSS added by the diagnostic snapshots taken inside the interval (the phase's own "before" snapshot and any mid-phase snapshot), recorded as `<checkpoint>SnapshotRssKb`; the RSS and Anonymous deltas include it. – = not recorded by this collection.

| Cell | Runs | Phase | Point | Heap | RSS | Anonymous | Pss_File | Snapshot cost |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | 5 | install | installChurnCheckpoint | – | 17.1 (-15.1–37.9) | 17.1 (-15.1–37.9) | 0.0 (-1.3–0.0) | 0.0 (0.0–0.0) |
| install n=10000 bridge on arena2 | 5 | install | installChurnAfterGc | -0.5 (-0.5–-0.3) | 16.1 (-15.3–37.3) | 16.1 (-15.3–37.3) | 0.0 (-1.3–0.0) | 0.0 (0.0–0.1) |
| install n=10000 bridge on arena2 | 5 | install | installChurnAfterTrim | – | -212.0 (-235.7–-208.6) | -212.0 (-235.7–-208.6) | 0.0 (-1.3–0.0) | 0.0 (0.0–0.1) |
| install n=10000 bridge on nmt | 5 | install | installChurnCheckpoint | – | -24.2 (-36.8–92.5) | -24.2 (-36.8–92.5) | 0.0 (-0.0–0.0) | 0.1 (0.0–0.1) |
| install n=10000 bridge on nmt | 5 | install | installChurnAfterGc | -0.3 (-0.4–-0.0) | -24.6 (-35.3–92.3) | -24.6 (-35.3–92.3) | 0.0 (-0.0–0.0) | 0.1 (0.0–0.1) |
| install n=10000 bridge on nmt | 5 | install | installChurnAfterTrim | – | -299.0 (-308.2–-260.7) | -299.0 (-308.2–-260.7) | 0.0 (-0.0–0.0) | 0.3 (0.2–0.6) |
| install n=10000 bridge on plain | 5 | install | installChurnCheckpoint | – | -29.0 (-37.3–33.0) | -29.0 (-37.3–33.0) | 0.0 (-0.0–1.8) | 0.1 (0.1–0.2) |
| install n=10000 bridge on plain | 5 | install | installChurnAfterGc | -0.4 (-0.6–-0.2) | -29.1 (-36.3–32.4) | -29.1 (-36.3–32.4) | 0.0 (-0.0–1.8) | 0.1 (0.1–0.2) |
| install n=10000 bridge on plain | 5 | install | installChurnAfterTrim | – | -279.3 (-307.0–-271.5) | -279.3 (-307.0–-271.5) | 0.0 (-0.0–0.8) | 0.2 (0.2–0.5) |

## Install churn timing

| Cell | Close p50 ms | Close max ms | Ready p50 ms | Ready max ms | Provider threads at checkpoint |
| --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | 81.2 (75.5–100.5) | 97.3 (87.0–167.1) | 399.5 (395.2–477.5) | 460.3 (436.5–657.1) | 1.0 (1.0–1.0) |
| install n=10000 bridge on nmt | 75.8 (70.0–83.2) | 101.7 (88.3–147.6) | 396.6 (391.3–431.6) | 487.2 (428.0–589.6) | 1.0 (1.0–1.0) |
| install n=10000 bridge on plain | 76.7 (72.8–83.3) | 119.4 (98.0–173.5) | 406.2 (389.0–429.2) | 543.1 (475.2–683.4) | 1.0 (1.0–1.0) |

## glibc malloc (MB) and arenas

| Cell | Checkpoint | Arenas | Held from OS | Free in allocator | In use | Direct mmap |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | afterWarmupGc | 2 (2–2) | 288.1 (257.2–316.0) | 101.5 (70.8–145.0) | 176.1 (150.5–190.1) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | afterIdleGc | 2 (2–2) | 288.1 (257.2–318.8) | 136.0 (91.8–154.9) | 152.1 (151.2–165.4) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | structuralBefore | 2 (2–2) | 288.1 (257.2–318.8) | 126.5 (91.8–154.1) | 161.6 (151.3–165.4) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | structuralCheckpoint | 2 (2–2) | 384.9 (367.7–405.2) | 214.9 (208.2–231.9) | 170.5 (156.1–173.2) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | structuralAfterGc | 2 (2–2) | 384.9 (367.7–405.2) | 225.0 (215.5–237.4) | 154.2 (152.2–167.8) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | installChurnBefore | 2 (2–2) | 384.9 (367.7–405.2) | 224.6 (215.0–237.2) | 154.6 (152.7–168.0) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | installChurnCheckpoint | 2 (2–2) | 419.3 (409.9–447.0) | 255.2 (245.8–269.3) | 164.4 (164.1–177.8) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | installChurnAfterGc | 2 (2–2) | 419.3 (409.9–447.0) | 263.3 (253.8–279.6) | 156.1 (152.2–169.6) | 27.2 (14.2–27.2) |
| install n=10000 bridge on arena2 | installChurnAfterTrim | 2 (2–2) | 415.2 (392.4–446.9) | 258.7 (235.9–277.1) | 156.5 (152.2–170.0) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | afterWarmupGc | 65 (64–71) | 432.3 (391.9–491.6) | 252.1 (229.8–291.6) | 197.0 (150.9–202.5) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | afterIdleGc | 65 (64–71) | 434.1 (392.9–492.9) | 271.5 (240.8–342.9) | 152.1 (150.0–165.5) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | structuralBefore | 65 (64–71) | 434.4 (393.2–493.2) | 271.3 (246.1–347.0) | 147.1 (146.1–165.5) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | structuralCheckpoint | 73 (68–76) | 734.6 (579.5–826.5) | 564.1 (409.4–668.3) | 170.1 (156.8–171.0) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | structuralAfterGc | 73 (68–76) | 734.7 (579.5–826.7) | 567.9 (412.8–673.0) | 166.7 (153.7–168.0) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | installChurnBefore | 73 (68–76) | 734.7 (579.6–826.9) | 567.9 (431.8–679.0) | 148.6 (147.7–166.8) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | installChurnCheckpoint | 73 (68–76) | 752.5 (589.1–946.5) | 574.8 (428.6–785.7) | 161.8 (160.5–178.8) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | installChurnAfterGc | 73 (68–76) | 752.5 (589.1–946.5) | 583.0 (436.9–794.0) | 153.0 (152.2–169.5) | 27.2 (14.2–27.2) |
| install n=10000 bridge on plain | installChurnAfterTrim | 73 (68–76) | 752.4 (589.2–929.7) | 582.9 (437.0–777.2) | 153.0 (152.2–170.0) | 27.2 (14.2–27.2) |

## NMT committed (MB): phase deltas per category

Only categories whose median delta is at least 1 MB in some cell, plus Total and malloc.

| Cell | Point | Total | malloc | Arena Chunk | Code | Compiler | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on nmt | installChurnCheckpoint | -35.0 (-44.8–-31.0) | 10.4 (7.9–12.0) | 1.3 (-1.3–1.8) | 13.8 (11.0–14.7) | 0.0 (-0.0–0.6) | -0.9 (-1.1–-0.9) | -50.0 (-60.0–-48.0) | 1.7 (1.6–1.7) |
| install n=10000 bridge on nmt | installChurnAfterGc | -45.9 (-57.1–-44.6) | -2.3 (-3.5–3.6) | -0.3 (-1.7–-0.1) | 3.2 (2.3–3.6) | 1.7 (1.4–1.8) | -1.2 (-1.3–-1.0) | -50.0 (-60.0–-48.0) | 0.7 (0.6–0.8) |
| install n=10000 bridge on nmt | installChurnAfterTrim | -45.5 (-56.7–-44.2) | -1.9 (-3.2–4.0) | 1.7 (0.2–1.7) | 3.2 (2.3–3.6) | 0.1 (0.1–0.1) | -1.2 (-1.3–-1.0) | -50.0 (-60.0–-48.0) | 0.7 (0.6–0.8) |

Absolute committed at every checkpoint (MB):

| Cell | Checkpoint | Total | malloc | Arena Chunk | Code | Compiler | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on nmt | afterWarmupGc | 964.3 (933.7–1007.5) | 68.3 (40.7–94.9) | 28.8 (1.3–55.4) | 24.4 (24.0–24.5) | 0.3 (0.3–0.4) | 76.8 (75.6–76.9) | 810.0 (756.0–810.0) | 14.4 (14.4–14.4) |
| install n=10000 bridge on nmt | afterIdleGc | 953.7 (896.4–953.9) | 40.4 (38.6–40.6) | 0.1 (0.0–0.9) | 24.4 (23.4–24.7) | 1.0 (0.3–1.1) | 76.8 (75.6–76.8) | 810.0 (756.0–810.0) | 14.6 (14.6–14.7) |
| install n=10000 bridge on nmt | structuralBefore | 950.3 (895.1–952.6) | 37.3 (36.2–39.4) | 0.7 (0.6–1.7) | 21.8 (20.0–23.7) | 0.4 (0.3–0.4) | 76.7 (75.6–76.7) | 810.0 (756.0–810.0) | 14.6 (14.6–14.7) |
| install n=10000 bridge on nmt | structuralCheckpoint | 943.4 (891.9–954.1) | 45.7 (43.8–46.4) | 1.8 (0.1–2.1) | 29.2 (28.2–29.6) | 0.4 (0.3–0.4) | 76.5 (75.4–76.6) | 792.0 (742.0–802.0) | 16.1 (16.0–16.1) |
| install n=10000 bridge on nmt | structuralAfterGc | 938.7 (887.1–949.9) | 41.0 (40.8–42.1) | 0.3 (0.2–1.8) | 25.7 (25.4–26.1) | 0.3 (0.3–0.4) | 76.4 (75.4–76.5) | 792.0 (742.0–802.0) | 16.1 (16.0–16.1) |
| install n=10000 bridge on nmt | installChurnBefore | 938.7 (887.1–949.9) | 40.9 (35.1–42.1) | 0.3 (0.3–1.8) | 25.5 (20.4–26.1) | 0.3 (0.3–0.3) | 76.4 (75.4–76.5) | 792.0 (742.0–802.0) | 16.1 (16.0–16.1) |
| install n=10000 bridge on nmt | installChurnCheckpoint | 903.8 (856.2–907.4) | 51.3 (47.2–52.3) | 1.5 (0.5–2.2) | 39.7 (31.4–40.2) | 0.3 (0.3–0.8) | 75.4 (74.5–75.5) | 740.0 (694.0–742.0) | 17.7 (17.7–17.8) |
| install n=10000 bridge on nmt | installChurnAfterGc | 890.1 (842.5–892.9) | 38.6 (38.6–38.7) | 0.0 (0.0–0.2) | 28.4 (23.9–29.1) | 2.0 (1.7–2.1) | 75.2 (74.3–75.3) | 740.0 (694.0–742.0) | 16.8 (16.7–16.8) |
| install n=10000 bridge on nmt | installChurnAfterTrim | 890.4 (843.0–893.2) | 39.0 (39.0–39.2) | 2.0 (2.0–2.0) | 28.4 (24.0–29.1) | 0.4 (0.4–0.4) | 75.2 (74.2–75.3) | 740.0 (694.0–742.0) | 16.8 (16.7–16.8) |

## Retained heap by group after GC (class histogram)

| Cell | Checkpoint | Bridge MB | Bridge objects | JavaFX MB | JavaFX objects | Other MB | TextField | Button | Scene | VBox |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=10000 bridge on arena2 | afterWarmupGc | 1.15 (1.15–1.15) | 30066 (30066–30067) | 104.13 (104.12–104.13) | 3489399 (3489268–3489421) | 69.41 (69.31–69.45) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | afterIdleGc | 1.15 (1.15–1.15) | 30066 (30066–30067) | 104.18 (104.18–104.18) | 3491000 (3490869–3491022) | 69.45 (69.36–69.50) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | structuralBefore | 1.15 (1.15–1.15) | 30067 (30066–30068) | 104.18 (104.18–104.18) | 3491008 (3490869–3491064) | 69.45 (69.35–69.49) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | structuralAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491669 (3491541–3491781) | 69.90 (69.77–70.62) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | installChurnBefore | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491669 (3491541–3491781) | 69.90 (69.77–70.62) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on arena2 | installChurnAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30069) | 104.20 (104.20–104.21) | 3491669 (3491541–3491949) | 70.78 (70.49–71.32) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | afterWarmupGc | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.12 (104.12–104.13) | 3489268 (3489268–3489428) | 69.33 (69.20–69.75) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | afterIdleGc | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.18 (104.18–104.18) | 3490869 (3490869–3491029) | 69.40 (69.25–69.80) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | structuralBefore | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.18 (104.18–104.18) | 3490869 (3490869–3491029) | 69.39 (69.24–69.80) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | structuralAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491557 (3491510–3491684) | 70.28 (69.79–70.47) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | installChurnBefore | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491557 (3491510–3491684) | 70.28 (69.79–70.48) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on nmt | installChurnAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491557 (3491510–3491684) | 71.08 (70.77–71.65) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | afterWarmupGc | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.13 (104.12–104.13) | 3489366 (3489289–3489397) | 69.41 (69.38–69.46) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | afterIdleGc | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.18 (104.18–104.18) | 3490980 (3490967–3490998) | 69.49 (69.43–69.51) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | structuralBefore | 1.15 (1.15–1.15) | 30067 (30066–30067) | 104.18 (104.18–104.18) | 3490980 (3490967–3490998) | 69.48 (69.43–69.51) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | structuralAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491658 (3491554–3491770) | 69.99 (69.22–70.71) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | installChurnBefore | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491658 (3491554–3491770) | 69.99 (69.22–70.71) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| install n=10000 bridge on plain | installChurnAfterGc | 1.15 (1.15–1.15) | 30068 (30068–30068) | 104.20 (104.20–104.20) | 3491658 (3491554–3491770) | 70.80 (69.84–71.53) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |

## Largest retained-heap growth per class (bridge on, plain; after GC − before)


install n=10000 bridge on plain:

| Class | Module | Δ instances | Δ KB |
| --- | --- | ---: | ---: |
| `[Ljava.lang.Object;` | java.base | 20 (20–20) | 345.8 (345.8–345.8) |
| `[Ljava.util.HashMap$Node;` | java.base | 4 (4–4) | 320.0 (320.0–320.0) |
| `[Ljdk.internal.vm.FillerElement;` | java.base | 17 (-7–56) | 117.7 (-68.2–372.7) |
| `[B` | java.base | 164 (143–234) | 9.6 (8.7–12.5) |
| `java.lang.Class` | java.base | 42 (42–42) | 5.0 (5.0–5.0) |
| `java.lang.String` | java.base | 164 (143–234) | 3.8 (3.4–5.5) |
| `java.lang.invoke.MemberName` | java.base | 64 (64–64) | 2.5 (2.5–2.5) |
| `dev.desktopcontrol.fxlab.BenchApp$Generation` | dev.desktopcontrol.fxlab | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.CountDownLatch$Sync` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `[D` | java.base | 2 (2–2) | 1.6 (1.6–1.6) |
| `java.util.concurrent.CountDownLatch` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.util.concurrent.atomic.AtomicInteger` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.lang.invoke.BoundMethodHandle$Species_LLLL` | java.base | 31 (31–31) | 1.5 (1.5–1.5) |
| `java.lang.invoke.ResolvedMethodName` | java.base | 48 (48–48) | 1.1 (1.1–1.1) |

## pmap: anonymous versus file-backed RSS (MB)

Java heap: anonymous mappings inside the Java heap range. "recorded heap range" uses the reserved range the bench read from GC.heap_info; "legacy heuristic" (collections without it) counts every anonymous mapping below 0x100000000000, which with compressed oops is the heap plus class space. Outside Java heap: malloc arenas, thread stacks, class space and other native mappings.

| Cell | Checkpoint | Anonymous RSS | Java heap | Outside Java heap | File RSS | Anonymous mappings | Split |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| install n=10000 bridge on arena2 | afterWarmupGc | 858.5 (815.8–875.7) | 547.3 (545.9–549.6) | 311.5 (269.8–326.8) | 110.9 (110.8–111.0) | 188 (186–191) | recorded heap range |
| install n=10000 bridge on arena2 | afterIdleGc | 858.9 (817.1–876.1) | 548.1 (547.0–550.4) | 311.7 (270.2–327.0) | 111.0 (110.8–111.0) | 188 (186–191) | recorded heap range |
| install n=10000 bridge on arena2 | structuralBefore | 858.9 (817.2–876.1) | 548.1 (547.0–550.4) | 311.7 (270.2–327.0) | 111.0 (110.8–111.1) | 188 (186–191) | recorded heap range |
| install n=10000 bridge on arena2 | structuralCheckpoint | 1128.9 (1110.7–1180.2) | 719.6 (718.3–759.4) | 410.6 (388.7–420.8) | 114.6 (114.5–114.8) | 190 (189–197) | recorded heap range |
| install n=10000 bridge on arena2 | structuralAfterGc | 1132.4 (1113.0–1182.4) | 722.1 (721.8–761.6) | 410.6 (388.7–420.8) | 114.6 (114.5–114.8) | 190 (189–197) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnBefore | 1132.4 (1113.0–1182.4) | 722.1 (721.8–761.6) | 410.6 (388.7–420.8) | 114.6 (114.5–114.8) | 190 (189–197) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnCheckpoint | 1149.5 (1117.3–1174.1) | 674.6 (656.8–708.9) | 465.2 (459.6–481.3) | 114.6 (114.5–114.8) | 191 (189–197) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnAfterGc | 1148.5 (1117.2–1173.8) | 675.1 (657.7–709.4) | 464.5 (458.7–480.2) | 114.6 (114.5–114.8) | 204 (203–209) | recorded heap range |
| install n=10000 bridge on arena2 | installChurnAfterTrim | 909.0 (896.9–952.2) | 675.1 (657.7–709.4) | 239.2 (228.0–242.8) | 114.6 (114.5–114.8) | 204 (203–209) | recorded heap range |
| install n=10000 bridge on plain | afterWarmupGc | 916.2 (873.5–942.6) | 553.6 (529.9–590.0) | 352.6 (334.5–381.8) | 110.9 (110.8–111.0) | 311 (307–325) | recorded heap range |
| install n=10000 bridge on plain | afterIdleGc | 917.6 (876.1–945.2) | 553.9 (531.0–590.7) | 354.5 (335.2–383.5) | 110.9 (110.8–111.0) | 311 (308–325) | recorded heap range |
| install n=10000 bridge on plain | structuralBefore | 918.0 (876.5–945.7) | 553.9 (531.0–590.7) | 355.0 (335.6–384.0) | 110.9 (110.8–111.0) | 311 (308–325) | recorded heap range |
| install n=10000 bridge on plain | structuralCheckpoint | 1308.2 (1255.6–1363.4) | 753.0 (719.3–766.0) | 564.8 (502.6–599.5) | 114.3 (114.2–114.4) | 325 (322–333) | recorded heap range |
| install n=10000 bridge on plain | structuralAfterGc | 1311.1 (1259.1–1367.2) | 756.3 (721.2–767.1) | 565.8 (502.9–600.8) | 114.3 (114.2–114.4) | 325 (322–333) | recorded heap range |
| install n=10000 bridge on plain | installChurnBefore | 1311.2 (1259.3–1367.6) | 756.3 (721.2–767.1) | 565.9 (503.1–601.2) | 114.3 (114.2–114.4) | 325 (322–333) | recorded heap range |
| install n=10000 bridge on plain | installChurnCheckpoint | 1302.5 (1221.8–1397.5) | 706.4 (675.3–715.1) | 591.5 (515.4–682.4) | 114.3 (114.2–114.4) | 325 (322–335) | recorded heap range |
| install n=10000 bridge on plain | installChurnAfterGc | 1303.7 (1223.1–1396.7) | 708.4 (675.3–715.1) | 590.6 (514.7–681.6) | 114.3 (114.2–114.4) | 339 (334–347) | recorded heap range |
| install n=10000 bridge on plain | installChurnAfterTrim | 1026.0 (977.4–1088.3) | 708.4 (675.3–715.1) | 313.0 (277.1–373.2) | 114.3 (114.2–114.4) | 339 (334–347) | recorded heap range |
