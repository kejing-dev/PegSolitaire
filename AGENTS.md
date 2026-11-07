# 孔明棋 Peg Solitaire: project guidance

English 33-hole peg solitaire for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package `io.embodit.pegsolitaire`, v0.1.0.
Design contract: spatial-app-factory `apps/peg-solitaire/design-package.json` (v1.2+, gate B 2026-11-05: the classical
malachite set after the user's reference `design/ref-malachite.jpg`, procedural board + glossy malachite balls, sample v3).
Profile A12: one main loop; `v2[]` is not built (no drag-to-hop, no 37-hole / triangle boards, no free mode, no redo /
replay / share codes, no second skin). Gate B question 1 took the sample's recommended default: six classic start boards
(standard / diamond / plus / fireplace / pyramid / arrow), each with its own records, on the same board, solver and panels.
Skeleton from Abalone Push (itself Royal Ur <- Five Stones <- kejing-dev/CloudAir v1.0.2): two containers, theme, sound
pool, level gate, factory board-input template; the rules, solver, flow, scene, models and pages are new.

## Surfaces
- **Board volume** (default container `PegBoard`, Volumetric 0.70 x 0.60 x 0.48 m, fixed scale, gravity aligned, uniform
  resize). `board.glb` (malachite inlay disc Ø 0.42 m, 3 cm thick, 33 ivory cups with dimples, two pink-brown flowers,
  gilt seams / centre frame / rim) on `tiltRoot` (0 / 30 / 45 / 60 / 90°, default 30°, pivot on the near-bottom edge,
  the near edge moves back as it tips so the hop stays in the volume). 33 hole holders `hole:<k>` (collider + interactable
  + system HoverEffect, a 5 mm marker one half step darker than the ivory as the visible child). 32 balls `peg:<i>`
  (`ball_0..5.glb`, collider + interactable + hover on the GLB clone; no collider while in the tray). `tray.glb` sits flat on
  the floor right of the board (never tilts, so balls stay in at 90°). Rings: ice-cyan select, amber floating targets
  (`hole:<k>` with their own collider, 4 cm over the hole on the flat board, 1.2 cm from 30°, a thin post to the cup),
  pink "jumped", red flash / stuck, gilt landing glow / result glow, white hover. Hint: cyan ghost ball + solid dots (sure),
  grey ghost + sparse dots (unsure), nothing (impossible). Counter plate (AttachmentPanel 148 x 58 dp ~ 102 x 40 mm) above
  the far rim: 「剩 n」 only.
- **Control window** (`PegControl`, Planar 560 x 600 dp, `Placement.right(PegBoard)`): native SpatialUI `TabBar`, three
  pages 对局 / 规则 / 设置. Game page: status sentence, 「剩 · 跳 · 步」 + record, the six start boards, the hint / coach
  line, [悔一步] [给个提示], [静音] [重开] (result: [再来一盘] [换个起始图], then [悔一步] [静音] [重开]). Confirmations are a
  whole-page card that locks the volume. Settings: language, sound, volume (3 snaps), reduce motion, tilt, coach,
  [重开控制窗] [清除记录], about, [退出应用] (closes both containers, removes the tasks, exits).

## Code
| Path | What |
|---|---|
| `domain/Peg.kt` | 33 holes, 76 jumps, 33-bit boards, results, move counting (same peg in a row = 1 move), 8 symmetries, position class |
| `domain/Solver.kt` | hint solver: DFS, failure table on canonical boards (primitive long set, 1 M cap), class pruning, 2 s / 3 M nodes |
| `domain/Starts.kt` | the six start boards |
| `domain/Game.kt` | settings, records, `SaveCodec` (`files/game.txt`: start + jumps, replayed and checked on load), `Placement` (which ball where) |
| `domain/GameFlow.kt` | the single `PlayState`, phases S00..S09, timing (input locked 260 ms per hop), undo, hint, confirms, records, save |
| `domain/Layout.kt` | metres for everything per tilt (`Geo`), tray seats, eyes, occlusion checks (unit tested) |
| `ui/board/*` | `BoardScene` (entities; per-frame motion in `onFrame` driven by `BoardFrameSystem`), `BoardScreen`, `BoardNames`, `input/BoardInput.kt` (factory template + GazeTargets) |
| `ui/control/*` | control window pages; every Text logs `OVERFLOW <tag>` when cut |
| `platform/*` | Application / Graph, SoundPlayer, WindowCtl (+ LevelGate); debug builds add `DebugCommandReceiver` |
| `tools/` | `build_models.py` (GLBs + baked malachite textures), `import_cc0_sounds.py`, `render_mix.py`, `build_icon.py`, `strings.py`, `ps.sh` |

Rules - state - render: the scene only renders `PlayState`. Volume input goes through the board-input template (D50: short
drags count as taps, targets resolved up the parent chain by name, hover callback); `BoardNames.dispatch` turns a hit into
`GameFlow.tapHole` / `tapEmpty`, the same calls the debug broadcast makes.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # rules, solver bench, flow, layout / occlusion per tilt, hit replay
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; python3 tools/render_mix.py ; python3 tools/build_icon.py --icon512 icon.png ; python3 tools/strings.py
DEV=emulator-5560 tools/ps.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow peg-solitaire` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.pegsolitaire.DEBUG -p io.embodit.pegsolitaire --es cmd <cmd> --es a "<arg>"` (or
`tools/ps.sh cmd <cmd> [arg]`). Log tag `PegSolitaire`. Holes: `d4` (centre) or 0..32.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `goto S00`, force-stop + launch, capture within 10 s, `s00hold off` | flat dark disc stand-in until board.glb is in |
| S01 第一次 | `goto S01` | standard board + rules page with [开始]; a tap on a peg that can hop ends it |
| S03 待跳 | `goto S03` (`coach on` for the first-game line) | |
| S04 已选 | `goto S04` | d6 selected: amber floating target d4, pink ring on d5 |
| S05 跳子中 | `goto S05 [ms]` | d6 -> d4 held at ms (default 150) by freeze; `freeze off` |
| S06 求解中 | `goto S06` | hint on from the opening (answers SURE quickly; the line shows 「在算…」 only past 200 ms) |
| S09 结局 | `goto S09 full` / `goto S09 one` / `goto S09 stuck` | Bergholt 18 moves / one left on d7 / stuck after 6 hops (26 left) |
| S11 设置 / S12 规则 | `goto S11` / `goto S12` (`history on` for 来历) | |
| S13 确认 | `goto S13 restart` / `goto S13 change` / `goto S13 exit` / `goto S13 clear` · `confirm` / `no` | |
| S16 恢复 | make some hops, force-stop, relaunch | back on the same board (log `GAME restored … S16`) |
Other: `tap <hole|empty>`, `replay <hole> [hand-ray|eye-hand|controller]` (real entities -> resolveUp -> listener; logs
`REPLAY … -> square=`), `jump <from> <to>`, `script bergholt|stuck6|offcentre [gapMs]`, `undo`, `undoto N`, `restart`,
`again`, `start <id>` (asks mid-game), `new <id>`, `hint on|off`, `lang zh|en|system`, `tilt 0|30|45|60|90`, `sound on|off`,
`volume 0-2`, `reduced on|off`, `coach on|off`, `load <hex>`, `freeze MS|off`, `welcome`, `page play|rules|settings`,
`reopen`, `closepanel`, `hover <hole>`, `dump`, `evidence`.

Screenshot list for QA: S00, S01, S03 (zh / en), S04, S05, S06 (hint sure / unsure / impossible: `load` a dead board then
`hint on`), S09 × 3, S11 (zh / en), S12 (+ 来历), S13 × 4, S16, tilt 0 / 30 / 45 / 60 / 90, reduced motion, mute.

## Known
- 3D taps (balls, holes, targets) are not reachable by `pico-cli interact` on the Mac emulator; they were driven by the debug
  broadcast through the same dispatch (`tap`) and by `replay` through the real entities. Controller ray / hand ray /
  gaze + pinch: headset only.
- Sounds are SoundPool (not spatial). All are CC0 recordings (build/sound-sources.md); selecting and hovering are silent.
- Hover: system HoverEffect on the hole markers and balls; the app's own white ring only appears if Shared Space delivers
  hover events (it did not on the Go-9 headset test).
