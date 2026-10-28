# Light native UI

The main app uses a restrained, light utility style rather than a marketing
layout or a WeChat clone. It remains a native Android View implementation with
no Compose or Material dependency added.

## Visual system

- Background: cool light grey.
- Content groups: white, 8dp radius, 1dp neutral border.
- Primary action: deep green with white icon and label.
- Running, paused and stopping states use separate green, amber and blue
  accents. A text label is always present, so state is not conveyed by colour
  alone.
- Text uses the system sans-serif family with medium weight for headings and
  controls.
- Icons are a single Lucide-style line-icon set stored as Android vectors.
- Settings use switches for binary controls and list rows for commands.
- Settings include one transport switch for route C. Route C is the default;
  route-B controls appear only in the separate fallback build.

## Screen structure

- Home: current agent state and primary control, environment readiness, latest
  result, then compact entries for memory and settings.
- Settings: grouped control switches, runtime permissions and advanced tools.
- Memory: four inline metrics, contact rows with individual management buttons,
  separate pending A0 and conflict review sections, per-contact
  retention/deletion and a separate global destructive action.
- Diagnostics: grouped capture/OCR and input/send commands plus a screenshot
  preview.
- Dialogs: shared light theme, rounded window, consistent accent colours and
  red confirmation text for destructive actions.

The bubble keeps its compact dark overlay styling because it must remain legible
over unrelated apps.
