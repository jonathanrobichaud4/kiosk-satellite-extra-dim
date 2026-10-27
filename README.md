# Extra Dim Control

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that turns Android's **Extra dim** on and off. Samsung calls it Extra dim in One UI and Android stores it as the secure setting `reduce_bright_colors_activated`. Dimming a wall tablet below its lowest brightness is handy at night, and this plugin lets Home Assistant or a kiosk gesture do it.

It works on Samsung tablets and any other Android 12 or newer device that has the same setting.

## What you get

- **Extra dim** switch in Home Assistant, kept in sync even when someone changes it from quick settings on the tablet.
- **Automatic mode** that turns Extra dim on when the room gets dark and off when it brightens, working alongside Kiosk Satellite's adaptive brightness.
- **Extra dim intensity** sensor showing the current level.
- **Automatic** sensor showing the reading automatic mode is following, handy for tuning thresholds.
- **Shizuku access** binary sensor, so you can see when control is unavailable.
- Three actions for **Gestures > Run a plugin action**, the kiosk drawer or ESPHome buttons: **Turn Extra dim on**, **Turn Extra dim off** and **Toggle Extra dim**.

## Requirements

- Kiosk Satellite 2026.10.42 or newer.
- [Shizuku](https://shizuku.rikka.app/guide/setup/) running on the tablet, with Kiosk Satellite granted access. Shell access started through wireless debugging is enough. Root is not needed.

Writing a secure setting needs the `WRITE_SECURE_SETTINGS` permission, which normal apps don't have. The Android shell user does, and Shizuku lets Kiosk Satellite run commands as that user.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Set intensity | Off | When on, also writes `reduce_bright_colors_level` every time Extra dim turns on. When off, the intensity chosen on the tablet is left alone |
| Intensity | 50% | The level to write, from 0 (mildest) to 100 (strongest). Shown only when Set intensity is on. Changing it while Extra dim is on applies it right away |
| Check state every | 15 s | How often the plugin reads the setting so Home Assistant notices changes made on the tablet |

### Automatic

| Setting | Default | What it does |
| --- | --- | --- |
| Automatic | Off | **Room light** follows the tablet's light sensor. **Panel brightness** follows the screen level that adaptive brightness sets, so Extra dim takes over once the panel is at its lowest |
| Turn on at or below | 5 lux or 2% | Extra dim turns on once the reading drops to this level |
| Turn off at or above | 20 lux or 10% | Extra dim turns off once the reading rises to this level. Keep it above the on level so it can't flicker |
| Wait before switching | 10 s | The reading has to stay past a threshold this long first, so a passing shadow or a lamp flicking on briefly doesn't trigger it |

Automatic mode only acts when the reading crosses a threshold. Turning Extra dim on or off yourself, from Home Assistant, a gesture or quick settings, sticks until the next crossing. The first reading after you choose a mode applies right away.

Panel brightness mode reacts to anything that lowers the panel, including Kiosk Satellite's screensaver dimming. Extra dim itself doesn't change the panel level, so the two can't fight each other.

## Commands it runs

All commands go through Kiosk Satellite's Shizuku connection:

```sh
settings get secure reduce_bright_colors_activated
settings get secure reduce_bright_colors_level
settings put secure reduce_bright_colors_activated 1   # or 0
settings put secure reduce_bright_colors_level <0-100>  # only with Set intensity on
```

Automatic mode only reads the light level and screen brightness that Kiosk Satellite already tracks. Nothing else is changed and no data leaves the tablet except the entity states Kiosk Satellite sends to your own Home Assistant.

## Installation

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/jonathanrobichaud4/kiosk-satellite-extra-dim` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Open the plugin's page, tap **Shizuku access** and approve the prompt on the tablet.

## Building

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP lands in `dist/`. For local testing use **Plugin Manager > Developer Tools > Install from ZIP**. To publish, create a GitHub release and let the included workflow build and attach the files.

## License

Apache-2.0. This plugin is not affiliated with Samsung.
