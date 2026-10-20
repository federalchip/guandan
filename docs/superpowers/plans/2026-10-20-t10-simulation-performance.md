# T10 Simulation, Replay and Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove seeded games terminate without invariant violations, reproduce any failure, and measure the v1 performance targets with real data.

**Architecture:** A production simulation runner drives the pure engine with the existing random legal policy and seed derivation. The CLI dispatches before Spring starts, streams simulation records, replays recorded actions, and benchmarks the existing fixed hands. JUnit properties and real HTTP measurements exercise separate correctness and latency boundaries.

**Tech Stack:** Java 17, Jackson, Spring Boot 3.5.16, JUnit Jupiter, QuickTheories, Maven Wrapper.

**Spec:** `2026-10-06-guandan-v1-mvp.md` T10 and G sections; `backend/artifacts/{m2-smoke,t4-generation-baseline}.json` are baselines only.

## Global Constraints

- Preserve the fixed `gd-v1-level2` rules and the policy's player-view information boundary.
- Never expose game seeds or unplayed opponent cards through player HTTP DTOs.
- Keep default tests fast; tag bulk simulation `slow` and timing tests `performance`.
- Preserve existing uncommitted user changes; do not stage or commit unrelated files.

## Review Focus

- Replay rejects changed actions/events/results and reports the seed and step.
- Invalid actions cannot mutate state; every action preserves all 108 distinct IDs and increments version once.
- Simulated policy sees only `PlayerView` plus supplied legal actions.
- The 10,000 game number is an actual completed count, never an M2 estimate.
- Performance reports distinguish generator, serialization, normal HTTP batches, and human-finished long batches.

---

### Task 1: Seeded simulation and replay

**Files:** `backend/src/main/java/com/guandan/cli/{GuandanCli,SimulationRunner}.java`, `backend/src/main/java/com/guandan/GuandanApplication.java`, `backend/src/test/java/com/guandan/engine/{SimulationTest,ReplayTest}.java`.

- [ ] Add failing tests: 100 seeded games terminate and a saved record reproduces result, actions and public events; tampering fails replay.
- [ ] Run focused tests and confirm the expected failure.
- [ ] Implement pure runner, JSON record, CLI simulate/replay dispatch before HTTP startup; nonzero failure exit and failure fixture.
- [ ] Run focused tests, package jar, smoke CLI, then 1,000 and 10,000 seeded games with 512 action guard.

### Task 2: State and generator properties

**Files:** `backend/src/test/java/com/guandan/engine/GamePropertiesTest.java` and shared simulation assertions.

- [ ] Add seeded property tests for ID conservation/ownership, monotone discarded, live current player, legal generated actions and version advance; failure text includes seed and action step.
- [ ] Run tests and inspect failures, then fix confirmed defects with a saved regression fixture before rerunning.

### Task 3: Fixed input and HTTP performance

**Files:** existing `GenerationBenchmarkTest`/`benchmark_hands.json`, new performance test or CLI support, `backend/artifacts/t10-*.json`.

- [ ] Reuse B01–B04 lead/follow inputs with 10 warmups and 100 measured invocations; record environment and compare with T4 baseline.
- [ ] Measure legal-actions candidate count, response bytes and serialization; measure at least 100 fresh normal HTTP command batches and at least 100 human-finished long batches with fixed seeds/fixtures.
- [ ] Report p50/p95/max and each G-section target without merging long batches into normal batches.

### Task 4: Evidence and stage gate

**Files:** `docs/test-report-v1.md`, `README.md`/handoff status as appropriate.

- [ ] Run backend standard/slow/performance tests, package, CLI replay, and `git diff --check`.
- [ ] Report actual machine/JDK, times, 1,000/10,000 results, any failures and remaining T11 dependency; only mark T10 passed if all gates pass.
