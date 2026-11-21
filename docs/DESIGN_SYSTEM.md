# DontMissMOM design system

The app uses a navy foundation, blue for everyday navigation and information, and red/coral for SOS actions and incoming emergencies. The same typography, spacing, component shapes and hierarchy apply in both themes.

## Color roles

| Role | Light | Dark |
| --- | --- | --- |
| Brand primary | `#B42318` | `#FF5A52` |
| Emergency | `#D92D20` | `#FF5A52` |
| Emergency container | `#FEF3F2` | `#3A1515` |
| Background | `#F8FAFC` | `#0B1220` |
| Surface | `#FFFFFF` | `#111827` |
| Elevated surface | `#FFFFFF` | `#1F2937` |
| Primary text | `#101828` | `#F8FAFC` |
| Secondary text | `#475467` | `#CBD5E1` |
| Decorative border | `#E4E7EC` | `#334155` |
| Success | `#12B76A` | `#34D399` |
| Warning | `#F79009` | `#FBBF24` |
| Information / navigation | `#2563EB` | `#60A5FA` |

Resources live in `values/colors.xml` and `values-night/colors.xml`. Material's general `colorPrimary` intentionally maps to **information blue** so input focus, navigation, switches and informational actions do not become red. Brand `primary` and `danger` are explicit emergency roles.

Default filled buttons use navy. The home SOS button uses the filled emergency style; contact-level SOS buttons use a restrained emergency container. Locations, avatars, request badges and navigation use blue. Offline and incomplete-setup states use amber, with warning icons and written explanations. Confirmed operations use green and check icons. An alert accepted by the service is always described as **accepted for delivery**, not as received by a contact.

## Contrast and accessibility

- The light-theme success and warning palette colors are accents. Small text and meaningful icons use darker `success_ink` (`#067647`) and `warning_ink` (`#B54708`) for contrast. Dark mode uses the supplied green and amber directly.
- Emergency buttons use white on light-theme red (4.83:1), and navy on dark-theme coral (5.78:1). White on coral would only achieve 3.07:1.
- Light-theme blue on its pale container is 4.75:1. Success text on its pale container is 5.40:1; warning text on its pale container is 5.20:1.
- Decorative borders use the requested palette. Input/control outlines use stronger `control_outline` values so controls remain distinguishable.
- Emergency identity combines a bell/exclamation icon, an explicit emergency label, and typography. States are never communicated only through color.
- Text can wrap and scroll. Alert call/dismiss actions stay outside the scrolling details; narrow and landscape layouts retain access to them. Decorative icons and avatar initials are excluded from accessibility announcements. Voice playback changes are announced politely.

## Components and motion

- Use a 24 dp screen gutter, a 4/8 dp spacing rhythm, 14–18 dp button corners, 24 dp cards, and at least 48 dp touch targets.
- Cards use a 1 dp elevation and quiet borders. Dialogs use the elevated surface. Only the navy SOS panel uses a subtle gradient.
- Native Material ripples provide press feedback. Tab content enters over 160 ms with a 6 dp movement and fade; animations are skipped when system animations are disabled. Emergency content does not flash, pulse continuously, or delay actions.
- Main styles: `themes.xml`, `design_system.xml`, and `alert_styles.xml`. Color selectors retain distinct disabled states.

## Visual review

The existing UI suite captures Home, Circle, Settings and incoming alerts in both themes. The alert regression checks optional attachment visibility, replacement alerts, fixed action reachability, landscape and dismissal. Captures use fictional contacts and do not send real alerts.

Reviewed previews: [app overview](screenshots/design-system/overview.png), [Alert light/dark comparison](screenshots/design-system/alert-preview.png), [Circle dark](screenshots/design-system/circle-dark.png), and [Settings light](screenshots/design-system/settings-light.png).

Verification on 2026-09-25:

- Debug app and instrumentation APKs built successfully. All 18 unit tests passed.
- Android lint: zero errors; 86 nonblocking warnings remain, including dependency/deprecation and legacy-resource suggestions.
- All 14 UI regression tests passed on Android 8 / API 26 at the normal text size.
- The three visual/reachability tests passed at 200% system text size, including light/dark themes and landscape. Reviewed [large-text Home](screenshots/design-system/home-light-large-text.png) and [large-text landscape Alert](screenshots/design-system/alert-landscape-large-text.png). Screenshot capture waits for rotation/scroll rendering to settle; final large-text captures use the emulator's native display resolution.
- All 18 audited text/container color pairs meet 4.5:1 or better. This is a targeted color audit, not a complete accessibility certification.
- Android 16 / API 36 validation is incomplete: the emulator's System UI became unresponsive and blocked window focus. No app-source changes were made to bypass that failure. A stable API 36 device/emulator should be used for the remaining check.

Logs and raw captures are retained in the ignored `.work` directory. The installable debug build is `app/build/outputs/apk/debug/app-debug.apk`.
