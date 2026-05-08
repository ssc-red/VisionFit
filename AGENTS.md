# AGENTS

## Project map
- Single-module Android app in `app/`; entry point is `MainActivity` (`app/src/main/java/com/example/visionfit/MainActivity.kt`).
- Core state lives in `SettingsStore` (`app/src/main/java/com/example/visionfit/data/SettingsStore.kt`) backed by DataStore Preferences.
- Accessibility blocking runs in `AppBlockAccessibilityService` with an overlay UI (`app/src/main/java/com/example/visionfit/accessibility/AppBlockAccessibilityService.kt`, `BlockingOverlay.kt`).
- Workout camera + ML Kit pose detection is implemented in `WorkoutScreen` with `PoseRepCounter` (`app/src/main/java/com/example/visionfit/ui/WorkoutScreen.kt`, `util/PoseRepCounter.kt`).

## Architecture and data flow
- `SettingsStore.settingsFlow` is the single source of truth for credits, per-exercise seconds, and app rules; UI collects it via `collectAsState` in `MainActivity`.
- App block rules are serialized in DataStore as a single string (`app_rules`), using `package|MODE;...` (`SettingsStore.parseAppRules/serializeAppRules`).
- Credits are produced by workouts (`WorkoutScreen` -> `SettingsStore.addCreditsSeconds`) and consumed by the accessibility service every second while a blocked app is in foreground (`AppBlockAccessibilityService` loop).
- Reels-only detection is heuristic: `AppBlockAccessibilityService.isReelsEvent` checks text/class/view id tokens for "reel" and applies a short grace period.

## UI conventions
- Compose screens live under `app/src/main/java/com/example/visionfit/ui/` and are wired by a simple enum-based screen switch in `MainActivity`.
- Settings uses inline validation (digits-only) and pushes state changes to `SettingsStore` immediately (`SettingsScreen.ExerciseSecondsRow`).
- Blocked apps list is built from installed packages and filtered by query; app toggle + mode change call into `SettingsStore` (`BlockedAppsScreen`).

## Integrations and permissions
- Accessibility service is declared in `AndroidManifest.xml` and configured via `res/xml/app_block_accessibility_service.xml`.
- Camera permission and CameraX preview/analysis pipeline are used in `WorkoutScreen`; pose detection uses ML Kit accurate detector.
- App list uses `QUERY_ALL_PACKAGES` and launcher intent queries (`util/AppListProvider.kt`).

## Developer workflows
- Build/run: open the project in Android Studio and run the `app` configuration (per `README.md`).

## Implementation tips
- When adding new app-block logic, update both the service behavior and the serialized rule model in `SettingsStore`.
- When adding a new exercise, extend `ExerciseType` and update `PoseRepCounter.thresholdsFor` to keep rep counting consistent.

