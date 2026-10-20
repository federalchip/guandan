# V1 Four-Seat Table Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a player read every public play and pass around a four-sided table until the backend closes the trick.

**Architecture:** Add natural card faces to public `PLAYED` event DTOs and include the current round's public events in game snapshots. A frontend presentation reducer keeps each seat's latest play and pass marker until `TRICK_CLOSED`. Event playback uses longer, event-specific dwell times; the table lays out seats at bottom/left/top/right.

**Tech Stack:** Java 17/Spring Boot/JUnit, Vue 3/TypeScript/Vitest.

**Spec:** `docs/v1-experience-feedback.md`; the user selected Qoder's recommended backend event faces approach on 2026-10-19.

## Global Constraints

- Keep the existing four-player rules and backend authority unchanged; only public played cards may gain faces.
- Preserve request idempotency, stale snapshot protection and the current session recovery behavior.
- Preserve existing user modifications in the dirty checkout; do not stage or commit unrelated files.

## Review Focus

- A previously beaten public play remains visible at its seat until `TRICK_CLOSED`.
- A pass never clears a seat's prior play, and a new play resets obsolete pass markers.
- Refresh reconstructs current-round public plays without exposing unplayed opponent cards.
- Stale callbacks, retries and animation cancellation cannot restore an older table.
- A long bot batch can be skipped by disabling animation, and the final snapshot stays authoritative.

---

### Task 1: Public event faces and recoverable round history

**Files:** `backend/src/main/java/com/guandan/api/dto/{DtoMapper,GameView}.java`, `backend/src/main/java/com/guandan/service/GameService.java`, `backend/src/test/java/com/guandan/{api/GameControllerTest,service/GameServiceTest}.java`, `docs/{api-v1.md,openapi.json}`, `frontend/src/api/schema.d.ts`.

**Interfaces:** `PLAYED.payload.played_cards: CardView[]`; `GameView.round_events: EventView[]` containing events after the last `TRICK_CLOSED`.

- [ ] Add failing backend tests for natural faces, public-only payload, and GET/command snapshot round history.
- [ ] Run focused tests and inspect expected failures.
- [ ] Map only played card IDs to natural `CardView`s; derive current-round event suffix under the game lock.
- [ ] Run focused tests, export OpenAPI, regenerate frontend types, update API docs.

### Task 2: Frontend table presentation and playback pacing

**Files:** `frontend/src/state/{tablePresentation,playback,game}.ts`, `frontend/src/__tests__/{tablePresentation,playback,command}.test.ts`.

**Interfaces:** Presentation state is four seat actions; reducer handles `PLAYED`, `PASSED`, `TRICK_CLOSED`; snapshot reconstruction consumes `round_events`. Scheduling accepts a dwell duration.

- [ ] Add failing tests for two seats' simultaneous plays, pass markers, trick close, snapshot recovery, event faces, and dwell durations.
- [ ] Run focused tests and inspect expected failures.
- [ ] Implement the reducer, wire it into live playback/snapshot adoption, and make pass/play dwell readable.
- [ ] Run focused tests and confirm stale/retry/animation paths remain sound.

### Task 3: Four-sided table and product verification

**Files:** `frontend/src/components/Table.vue`, `frontend/src/App.vue`, `frontend/src/__tests__/{table,app}.test.ts`, `docs/v1-experience-feedback.md`.

- [ ] Add failing component tests for seat positioning, per-seat cards/pass markers, and current-turn display.
- [ ] Run focused tests and inspect expected failures.
- [ ] Render seats bottom/left/top/right with their own public action areas; keep the hand and controls usable.
- [ ] Run backend and frontend test suites, typecheck/build, and inspect a real browser game.
- [ ] Mark implemented feedback and record any remaining limitation in the feedback document.
