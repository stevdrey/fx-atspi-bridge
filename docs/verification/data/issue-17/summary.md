# Memory diagnostics summary

Source: raw artifacts of the collection described in `environment.txt` (not committed; they contain local paths). Values: median (min–max) over repetitions; MB = 1024 KB.

## Phase deltas (MB, after − before)

| Cell | Runs | Phase | Point | Heap | RSS | Anonymous | Pss_File |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |
| install n=1000 bridge on arena2 | 3 | install | installChurnCheckpoint | – | 147.4 (146.3–148.4) | 147.3 (146.3–148.4) | 0.0 (0.0–0.0) |
| install n=1000 bridge on arena2 | 3 | install | installChurnAfterGc | -0.1 (-0.1–-0.0) | 148.9 (147.5–149.7) | 148.9 (147.5–149.6) | 0.0 (0.0–0.0) |
| install n=1000 bridge on arena2 | 3 | install | installChurnAfterTrim | – | 109.8 (105.0–111.6) | 109.8 (105.0–111.6) | 0.0 (0.0–0.0) |
| install n=1000 bridge on nmt | 3 | install | installChurnCheckpoint | – | 156.2 (155.7–157.0) | 156.2 (155.7–157.0) | 0.0 (0.0–0.0) |
| install n=1000 bridge on nmt | 3 | install | installChurnAfterGc | -0.0 (-0.2–0.1) | 158.0 (157.2–158.6) | 158.0 (157.2–158.6) | 0.0 (0.0–0.0) |
| install n=1000 bridge on nmt | 3 | install | installChurnAfterTrim | – | 101.2 (98.8–104.1) | 101.2 (98.8–104.1) | 0.0 (0.0–0.0) |
| install n=1000 bridge on plain | 3 | install | installChurnCheckpoint | – | 151.4 (148.9–157.8) | 151.4 (148.9–157.8) | 0.0 (0.0–0.0) |
| install n=1000 bridge on plain | 3 | install | installChurnAfterGc | -0.1 (-0.2–0.0) | 153.2 (150.6–159.2) | 153.2 (150.6–159.2) | 0.0 (0.0–0.0) |
| install n=1000 bridge on plain | 3 | install | installChurnAfterTrim | – | 94.5 (92.6–106.0) | 94.5 (92.6–106.0) | 0.0 (0.0–0.0) |
| structural n=10000 bridge off nmt | 3 | structural | structuralCheckpoint | – | 401.5 (348.2–439.3) | 397.9 (344.7–435.9) | 0.5 (-6.2–0.5) |
| structural n=10000 bridge off nmt | 3 | structural | structuralAfterGc | 1.1 (-0.8–1.3) | 404.7 (348.7–441.5) | 401.1 (345.2–438.1) | 0.5 (-6.2–0.5) |
| structural n=10000 bridge off plain | 3 | structural | structuralCheckpoint | – | 402.9 (349.4–448.1) | 399.4 (346.0–444.5) | 0.5 (-8.7–6.1) |
| structural n=10000 bridge off plain | 3 | structural | structuralAfterGc | 0.1 (0.1–1.5) | 404.7 (350.2–450.5) | 401.2 (346.9–447.0) | 0.5 (-8.7–6.1) |
| structural n=10000 bridge on nmt | 3 | structural | structuralCheckpoint | – | 310.9 (234.4–345.4) | 307.4 (231.0–341.8) | 0.5 (0.4–0.5) |
| structural n=10000 bridge on nmt | 3 | structural | structuralAfterGc | 3.9 (3.6–4.0) | 316.2 (236.8–348.5) | 312.7 (233.4–344.9) | 0.5 (0.4–0.5) |
| structural n=10000 bridge on plain | 3 | structural | structuralCheckpoint | – | 335.1 (213.5–389.5) | 331.5 (210.1–385.9) | 0.5 (0.4–0.5) |
| structural n=10000 bridge on plain | 3 | structural | structuralAfterGc | 3.8 (2.8–3.9) | 337.8 (217.5–392.8) | 334.1 (214.1–389.2) | 0.5 (0.4–0.5) |

## Install churn timing

| Cell | Close p50 ms | Close max ms | Ready p50 ms | Ready max ms | Provider threads at checkpoint |
| --- | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on arena2 | 13.7 (13.5–13.7) | 17.0 (16.6–17.1) | 45.8 (45.6–46.0) | 56.5 (56.3–57.7) | 1.0 (1.0–1.0) |
| install n=1000 bridge on nmt | 13.3 (13.2–13.4) | 15.6 (14.9–16.1) | 46.1 (45.9–46.1) | 57.4 (56.8–57.6) | 1.0 (1.0–1.0) |
| install n=1000 bridge on plain | 13.4 (13.4–13.5) | 16.5 (15.6–16.5) | 46.3 (45.8–46.6) | 58.8 (57.2–58.8) | 1.0 (1.0–1.0) |

## glibc malloc (MB) and arenas

| Cell | Checkpoint | Arenas | Held from OS | Free in allocator | In use | Direct mmap |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on arena2 | installChurnBefore | 2 (2–2) | 150.4 (147.4–157.3) | 93.6 (86.9–96.7) | 63.5 (50.7–63.7) | 14.2 (14.2–27.2) |
| install n=1000 bridge on arena2 | installChurnCheckpoint | 2 (2–2) | 151.8 (148.3–158.1) | 86.3 (80.1–89.3) | 71.6 (59.1–71.8) | 14.2 (14.2–27.2) |
| install n=1000 bridge on arena2 | installChurnAfterGc | 2 (2–2) | 151.8 (148.3–158.1) | 92.5 (86.1–95.5) | 65.7 (52.9–65.7) | 14.2 (14.2–27.2) |
| install n=1000 bridge on arena2 | installChurnAfterTrim | 2 (2–2) | 138.7 (119.5–146.1) | 73.0 (66.6–80.4) | 65.7 (52.9–65.7) | 14.2 (14.2–27.2) |
| install n=1000 bridge on plain | installChurnBefore | 65 (64–65) | 239.7 (224.3–243.6) | 176.1 (173.7–192.9) | 50.7 (50.6–63.7) | 27.2 (14.2–27.2) |
| install n=1000 bridge on plain | installChurnCheckpoint | 65 (64–65) | 255.1 (233.9–277.5) | 183.2 (175.0–219.1) | 59.0 (58.4–71.9) | 27.2 (14.2–27.2) |
| install n=1000 bridge on plain | installChurnAfterGc | 65 (64–65) | 260.0 (238.9–282.5) | 194.4 (186.2–229.9) | 52.7 (52.7–65.7) | 27.2 (14.2–27.2) |
| install n=1000 bridge on plain | installChurnAfterTrim | 65 (64–65) | 260.2 (239.1–282.6) | 194.5 (186.4–229.9) | 52.7 (52.7–65.7) | 27.2 (14.2–27.2) |
| structural n=10000 bridge off plain | structuralBefore | 65 (63–65) | 353.2 (307.2–383.3) | 315.1 (256.3–334.5) | 48.8 (38.1–50.8) | 14.2 (14.2–27.2) |
| structural n=10000 bridge off plain | structuralCheckpoint | 74 (65–74) | 536.8 (336.1–586.3) | 479.9 (278.0–535.5) | 56.9 (50.8–58.1) | 14.2 (14.2–27.2) |
| structural n=10000 bridge off plain | structuralAfterGc | 74 (65–74) | 536.9 (336.2–586.3) | 483.4 (283.2–538.9) | 53.0 (47.4–53.5) | 14.2 (14.2–27.2) |
| structural n=10000 bridge on plain | structuralBefore | 68 (68–68) | 462.5 (426.2–513.2) | 311.2 (262.5–363.6) | 151.3 (149.6–163.7) | 27.2 (14.2–27.2) |
| structural n=10000 bridge on plain | structuralCheckpoint | 74 (73–76) | 781.3 (632.4–859.0) | 619.2 (461.4–699.4) | 162.0 (159.6–171.0) | 27.2 (14.2–27.2) |
| structural n=10000 bridge on plain | structuralAfterGc | 74 (73–76) | 781.3 (632.6–859.0) | 623.6 (465.9–702.8) | 157.7 (156.2–166.7) | 27.2 (14.2–27.2) |

## NMT committed (MB): phase deltas per category

Only categories whose median delta is at least 1 MB in some cell, plus Total and malloc.

| Cell | Point | Total | malloc | Code | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on nmt | installChurnCheckpoint | 160.9 (160.8–160.9) | 7.2 (7.1–7.3) | 15.0 (15.0–15.2) | 3.1 (3.1–3.1) | 140.0 (140.0–140.0) | 1.7 (1.7–1.7) |
| install n=1000 bridge on nmt | installChurnAfterGc | 153.6 (153.5–153.6) | 0.9 (0.8–1.0) | 9.5 (9.4–9.6) | 3.0 (3.0–3.1) | 140.0 (140.0–140.0) | 0.7 (0.7–0.8) |
| install n=1000 bridge on nmt | installChurnAfterTrim | 153.6 (153.5–153.7) | 0.9 (0.8–1.0) | 9.5 (9.4–9.6) | 3.0 (3.0–3.1) | 140.0 (140.0–140.0) | 0.7 (0.7–0.8) |
| structural n=10000 bridge off nmt | structuralCheckpoint | 7.7 (6.6–9.8) | 4.1 (4.0–6.6) | 4.6 (4.0–4.8) | 0.1 (0.1–0.1) | 0.0 (0.0–0.0) | 1.5 (1.3–1.6) |
| structural n=10000 bridge off nmt | structuralAfterGc | 3.9 (3.0–4.1) | 0.6 (0.4–0.8) | 1.2 (0.7–1.6) | -0.0 (-0.0–0.0) | 0.0 (0.0–0.0) | 1.5 (1.3–1.6) |
| structural n=10000 bridge on nmt | structuralCheckpoint | -13.0 (-20.0–-6.0) | 5.9 (5.8–9.0) | 5.8 (5.6–6.0) | -0.4 (-0.5–-0.3) | -22.0 (-28.0–-18.0) | 1.5 (1.5–1.6) |
| structural n=10000 bridge on nmt | structuralAfterGc | -17.6 (-23.6–-10.8) | 2.1 (1.3–4.3) | 1.9 (1.5–2.2) | -0.4 (-0.5–-0.4) | -22.0 (-28.0–-18.0) | 1.5 (1.5–1.6) |

Absolute committed at the last point (MB):

| Cell | Point | Total | malloc | Code | GC | Java Heap | Metaspace |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on nmt | installChurnCheckpoint | 356.8 (356.7–357.0) | 47.9 (47.9–48.1) | 35.9 (35.7–36.0) | 63.7 (63.7–63.7) | 212.0 (212.0–212.0) | 16.1 (16.1–16.1) |
| install n=1000 bridge on nmt | installChurnAfterGc | 349.5 (349.4–349.7) | 41.7 (41.6–41.8) | 30.3 (30.1–30.4) | 63.6 (63.6–63.6) | 212.0 (212.0–212.0) | 15.2 (15.2–15.2) |
| install n=1000 bridge on nmt | installChurnAfterTrim | 349.5 (349.4–349.7) | 41.7 (41.6–41.8) | 30.3 (30.1–30.4) | 63.6 (63.6–63.6) | 212.0 (212.0–212.0) | 15.2 (15.2–15.2) |
| structural n=10000 bridge off nmt | structuralCheckpoint | 899.7 (786.7–951.9) | 42.4 (42.3–44.6) | 24.5 (24.4–25.0) | 75.6 (73.3–76.8) | 756.0 (648.0–810.0) | 14.1 (14.1–14.2) |
| structural n=10000 bridge off nmt | structuralAfterGc | 893.8 (783.1–948.3) | 38.8 (38.7–38.9) | 21.3 (21.0–21.6) | 75.6 (73.2–76.7) | 756.0 (648.0–810.0) | 14.1 (14.1–14.2) |
| structural n=10000 bridge on nmt | structuralCheckpoint | 931.8 (890.9–939.5) | 47.0 (45.7–49.2) | 29.0 (28.2–29.2) | 76.2 (75.3–76.4) | 782.0 (738.0–788.0) | 15.9 (15.9–16.0) |
| structural n=10000 bridge on nmt | structuralAfterGc | 928.2 (886.2–935.0) | 42.4 (42.0–44.5) | 24.8 (24.8–24.9) | 76.2 (75.2–76.3) | 782.0 (738.0–788.0) | 15.9 (15.9–16.0) |

## Retained heap by group after GC (class histogram)

| Cell | Checkpoint | Bridge MB | Bridge objects | JavaFX MB | JavaFX objects | Other MB | TextField | Button | Scene | VBox |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on arena2 | installChurnBefore | 0.18 (0.18–0.18) | 5052 (5052–5052) | 10.86 (10.86–10.86) | 364488 (364488–364488) | 13.83 (13.79–13.88) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| install n=1000 bridge on arena2 | installChurnAfterGc | 0.13 (0.13–0.13) | 4058 (4058–4058) | 10.86 (10.86–10.86) | 364488 (364488–364488) | 13.91 (13.71–13.91) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| install n=1000 bridge on nmt | installChurnBefore | 0.18 (0.18–0.18) | 5052 (5052–5052) | 10.86 (10.86–10.86) | 364488 (364488–364488) | 13.82 (13.73–13.91) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| install n=1000 bridge on nmt | installChurnAfterGc | 0.13 (0.13–0.13) | 4058 (4058–4058) | 10.86 (10.86–10.86) | 364488 (364488–364488) | 13.82 (13.79–13.86) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| install n=1000 bridge on plain | installChurnBefore | 0.18 (0.18–0.18) | 5052 (5052–5052) | 10.86 (10.86–10.86) | 364488 (364488–364489) | 13.84 (13.79–13.89) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| install n=1000 bridge on plain | installChurnAfterGc | 0.13 (0.13–0.13) | 4058 (4058–4058) | 10.86 (10.86–10.86) | 364488 (364488–364488) | 13.81 (13.77–13.97) | 0 (0–0) | 1000 (1000–1000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge off nmt | structuralBefore | 0.00 (0.00–0.00) | 0 (0–0) | 101.28 (101.28–101.28) | 3370852 (3370852–3371019) | 64.92 (64.76–65.15) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge off nmt | structuralAfterGc | 0.00 (0.00–0.00) | 0 (0–0) | 101.31 (101.30–101.32) | 3372061 (3371781–3372233) | 66.19 (64.53–66.21) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge off plain | structuralBefore | 0.00 (0.00–0.00) | 0 (0–0) | 101.28 (101.28–101.28) | 3370852 (3370852–3370929) | 64.88 (64.76–65.06) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge off plain | structuralAfterGc | 0.00 (0.00–0.00) | 0 (0–0) | 101.30 (101.30–101.30) | 3371682 (3371498–3371707) | 66.17 (65.38–66.49) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge on nmt | structuralBefore | 1.76 (1.76–1.76) | 50052 (50052–50052) | 101.28 (101.28–101.28) | 3370854 (3370854–3370873) | 69.22 (69.07–69.38) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge on nmt | structuralAfterGc | 1.76 (1.76–1.95) | 50053 (50053–54292) | 101.30 (101.30–101.30) | 3371535 (3371514–3371615) | 70.97 (70.91–71.34) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge on plain | structuralBefore | 1.76 (1.76–2.07) | 50052 (50052–56952) | 101.28 (101.28–101.29) | 3370855 (3370854–3371099) | 69.33 (69.22–69.47) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |
| structural n=10000 bridge on plain | structuralAfterGc | 1.76 (1.76–1.83) | 50053 (50053–51700) | 101.30 (101.30–101.30) | 3371652 (3371610–3371665) | 71.06 (70.59–71.14) | 0 (0–0) | 10000 (10000–10000) | 1 (1–1) | 0 (0–0) |

## Largest retained-heap growth per class (bridge on, plain; after GC − before)


install n=1000 bridge on plain:

| Class | Module | Δ instances | Δ KB |
| --- | --- | ---: | ---: |
| `[Ljava.lang.Object;` | java.base | 16 (16–16) | 12.9 (12.9–12.9) |
| `[Ljava.util.HashMap$Node;` | java.base | 1 (1–1) | 8.0 (8.0–8.0) |
| `[B` | java.base | 161 (159–175) | 7.8 (7.7–8.2) |
| `java.lang.Class` | java.base | 33 (33–33) | 4.0 (4.0–4.0) |
| `java.lang.String` | java.base | 161 (159–175) | 3.8 (3.7–4.1) |
| `dev.desktopcontrol.fxlab.BenchApp$Generation` | dev.desktopcontrol.fxlab | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.CountDownLatch$Sync` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.util.concurrent.locks.AbstractQueuedSynchronizer$SharedNode` | java.base | 100 (100–100) | 2.3 (2.3–2.3) |
| `java.lang.invoke.MemberName` | java.base | 55 (55–55) | 2.1 (2.1–2.1) |
| `java.lang.Integer` | java.base | 127 (127–127) | 2.0 (2.0–2.0) |
| `[D` | java.base | 2 (2–2) | 1.6 (1.6–1.6) |
| `java.util.concurrent.CountDownLatch` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.util.concurrent.atomic.AtomicInteger` | java.base | 100 (100–100) | 1.6 (1.6–1.6) |
| `java.lang.invoke.BoundMethodHandle$Species_LLLL` | java.base | 30 (30–30) | 1.4 (1.4–1.4) |
| `jdk.internal.util.WeakReferenceKey` | java.base | 36 (36–36) | 1.1 (1.1–1.1) |

structural n=10000 bridge on plain:

| Class | Module | Δ instances | Δ KB |
| --- | --- | ---: | ---: |
| `[Ljdk.internal.vm.FillerElement;` | java.base | 1134 (-323–8381) | 1791.5 (1380.0–1813.7) |
| `[B` | java.base | 11442 (11398–11554) | 346.6 (339.3–354.5) |
| `java.lang.String` | java.base | 11440 (11396–11551) | 268.1 (267.1–270.7) |
| `java.lang.Class` | java.base | 467 (444–470) | 55.7 (52.9–56.1) |
| `java.lang.invoke.MemberName` | java.base | 384 (371–389) | 15.0 (14.5–15.2) |
| `[Ljava.lang.Object;` | java.base | 281 (254–286) | 12.6 (-23.8–19.1) |
| `java.util.concurrent.ConcurrentHashMap$Node` | java.base | 445 (432–450) | 10.4 (10.1–10.5) |
| `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | java.base | 0 (0–0) | 8.0 (8.0–8.0) |
| `java.lang.invoke.ResolvedMethodName` | java.base | 270 (259–274) | 6.3 (6.1–6.4) |
| `java.util.HashMap$Node` | java.base | 224 (171–226) | 5.2 (4.0–5.3) |
| `[I` | java.base | 152 (151–153) | 4.7 (4.7–4.8) |
| `java.lang.invoke.DirectMethodHandle` | java.base | 135 (131–136) | 4.2 (4.1–4.2) |
| `java.lang.invoke.DirectMethodHandle$Constructor` | java.base | 107 (105–108) | 4.2 (4.1–4.2) |
| `java.lang.invoke.MethodType` | java.base | 130 (127–134) | 4.1 (4.0–4.2) |
| `jdk.internal.util.WeakReferenceKey` | java.base | 129 (127–133) | 4.0 (4.0–4.2) |

## pmap: anonymous versus file-backed RSS (MB)

JVM low range: anonymous mappings below 0x100000000000 (Java heap and class space with compressed oops); native range: everything above (malloc arenas, thread stacks, other native mappings).

| Cell | Checkpoint | Anonymous RSS | JVM low range | Native range | File RSS | Anonymous mappings |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| install n=1000 bridge on arena2 | installChurnBefore | 197.2 (190.9–199.5) | 60.3 (60.0–60.6) | 136.9 (130.3–139.5) | 111.0 (111.0–111.1) | 184 (183–185) |
| install n=1000 bridge on arena2 | installChurnCheckpoint | 344.6 (339.3–345.8) | 194.9 (193.7–195.2) | 149.7 (145.6–150.6) | 111.0 (111.0–111.1) | 183 (182–184) |
| install n=1000 bridge on arena2 | installChurnAfterGc | 346.1 (340.5–347.1) | 195.0 (193.6–195.0) | 151.1 (146.9–152.0) | 111.0 (111.0–111.1) | 197 (196–198) |
| install n=1000 bridge on arena2 | installChurnAfterTrim | 304.6 (302.5–307.0) | 195.0 (193.6–195.0) | 109.5 (108.9–112.0) | 111.0 (111.0–111.1) | 197 (196–198) |
| install n=1000 bridge on plain | installChurnBefore | 230.8 (230.7–240.6) | 60.6 (60.5–60.7) | 170.3 (170.0–179.9) | 111.2 (111.2–111.2) | 306 (306–307) |
| install n=1000 bridge on plain | installChurnCheckpoint | 381.7 (379.5–398.3) | 193.6 (193.5–195.3) | 188.2 (186.0–203.0) | 111.2 (111.2–111.2) | 305 (305–306) |
| install n=1000 bridge on plain | installChurnAfterGc | 383.9 (381.9–400.0) | 193.6 (193.6–195.1) | 190.3 (188.2–204.9) | 111.2 (111.2–111.2) | 320 (319–323) |
| install n=1000 bridge on plain | installChurnAfterTrim | 333.4 (325.0–337.0) | 193.6 (193.6–195.1) | 138.3 (131.4–143.4) | 111.2 (111.2–111.2) | 320 (319–323) |
| structural n=10000 bridge off plain | structuralBefore | 686.2 (672.9–702.9) | 440.7 (438.0–451.4) | 248.2 (232.2–251.5) | 111.0 (110.7–111.0) | 302 (296–308) |
| structural n=10000 bridge off plain | structuralCheckpoint | 1101.7 (1018.7–1130.6) | 765.2 (764.2–766.1) | 336.5 (252.6–366.4) | 114.5 (114.3–114.6) | 322 (301–328) |
| structural n=10000 bridge off plain | structuralAfterGc | 1103.7 (1019.7–1133.3) | 766.6 (766.3–766.9) | 336.8 (253.1–367.0) | 114.5 (114.3–114.6) | 322 (301–328) |
| structural n=10000 bridge on plain | structuralBefore | 1011.4 (932.8–1056.4) | 667.5 (573.5–669.9) | 359.3 (341.5–389.0) | 111.0 (110.7–111.2) | 320 (317–322) |
| structural n=10000 bridge on plain | structuralCheckpoint | 1318.1 (1221.2–1387.6) | 754.0 (747.4–761.8) | 564.1 (473.7–625.7) | 114.6 (114.3–114.7) | 335 (328–335) |
| structural n=10000 bridge on plain | structuralAfterGc | 1321.5 (1226.0–1390.4) | 756.9 (751.1–764.0) | 564.6 (474.9–626.4) | 114.6 (114.3–114.7) | 335 (328–335) |
