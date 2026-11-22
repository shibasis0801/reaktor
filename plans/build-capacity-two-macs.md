# Build capacity across the two Macs

Claude, 8 November 2026, during the BestBuds release.

## Where capacity goes today

| | Main Mac | m1-worker |
|---|---|---|
| Machine | MacBook Pro M4 Pro, 14 cores (10 performance), 48 GB | MacBook Pro M1 Pro, 10 cores (8 performance), 32 GB |
| At 21:37 IST | Load 6.5, 60% memory free, 1.8 GB swap, 24 GiB disk free, no routed build running or queued | Load 1.6 during build 42's iOS export, 39% memory free, 62 GiB disk free, the iPhone attached |

The router already prefers the main Mac. A routed build runs there when the Mac has 15% CPU idle and 20% memory free, under normal pressure and within the compression and swap limits. Otherwise it goes to a worker slot. Slot 0 is the worker's main checkout with the phones. Slots 1 and 2 need 40% of worker memory free. Config: `local_slots` 2, `worker_slots` 2, `local_max_workers` 12, `worker_slot_max_workers` 6.

Tonight the limit was not the number of slots:
- **Release work lines up on one host.** Build 42 runs Android, then iOS, one after the other on the slower worker, and holds its checkout. Work that only needs a frozen tree, such as the block fix, waited for that lease while the M4 Pro sat idle.
- **The main Mac's disk.** At 24 GiB free, a full release build (about 10 to 15 GB) would break the 20 GB floor, so release builds went to the worker.
- **The second local lane is narrow.** It accepts only isolated verification tasks and caps them at 4 Gradle workers, which is low for 48 GB.
- **The shared build cache barely applies.** The pilot cache only stores JVM compile tasks and jars, and only when a build asks for it, so neither Mac reuses the other's Kotlin/JS, Kotlin/Native or Android outputs.

## Tonight

Frozen-tree work runs on the main Mac beside build 42. It uses a new folder outside `~/dev`, so the IDE and Atlas don't index it, and outside `~/other/{tmp,temp,.tmp}`, so storage cleanup leaves it alone. It runs with `REMOTE_DEV_WORKER=1`, so nothing syncs to the worker, and stops if the disk falls below 20 GB free. The block fix's tests and Worker bundle use this lane now.

Router code and config stay unchanged until both store submissions are in, because every lane's build goes through the router.

## After launch (codex-platform implements, Claude reviews)

The owner approved this order on 8 Nov at about 21:50 IST: router changes start after both store submissions are in.

1. **Split release builds across both Macs.** Run Android on the main Mac and iOS on the worker at the same time, from one frozen snapshot, with one combined input comparison and audit. Release builds should take roughly half as long.
2. **Widen the second local lane.** Let it run any build from its frozen snapshot, not only verification, and raise its workers from 4 to 8 through a config key (`local_slot_max_workers`). Keep the main Mac's Gradle daemons warm for 30 minutes instead of 5. Tests in `test_remote_router.py`.
3. **Share the build cache for every task type.** Extend the cache to Kotlin/JS, Kotlin/Native and Android tasks whose outputs relocate, read it by default on both Macs, and let only release and main-line builds write to it.
4. **Put release builds first in the queue,** ahead of routine checks, with a visible reason in the waiting message.
5. **Give the main Mac disk headroom.** Triage `~/other/tmp` (16.9 GB, item other-tmp-triage) and the Xcode and Gradle caches, so a release build fits locally above the 20 GB floor.

The worker's deployed `run-gradle` and init scripts change only while it is idle.
