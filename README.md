# pteal79/signature-pad

Freehand signature pad for NativePHP Mobile v4 (EDGE). Native SwiftUI (iOS) and
Jetpack Compose (Android) renderer, exposed as `<native:signature-pad />`.

Requires `nativephp/mobile` ^4.6. No permissions, no extra dependencies.
Minimum OS: Android API 26, iOS 18.2 (same as NativePHP v4 / `nativephp/mobile-ui`).

## Install

```bash
composer require pteal79/signature-pad
php artisan native:plugin:register pteal79/signature-pad
php artisan native:plugin:list
```

## Usage

```blade
<native:signature-pad
    class="w-full aspect-[3/1] rounded-xl bg-white dark:bg-gray-900 border border-gray-300"
    :value="$signaturePath"
    :clear-token="$clearCount"
    :read-only="$locked"
    :stroke-width="2.5"
    pen-color="#111827"
    dark-pen-color="#F9FAFB"
    @change="onSignatureChanged"
/>
```

```php
public string $signaturePath = '';
public int $clearCount = 0;

public function onSignatureChanged(string $path): void
{
    $this->signaturePath = $path;   // '' means cleared
}

public function clearSignature(): void
{
    $this->clearCount++;            // pad wipes itself and fires @change("")
}
```

| Attribute | Type | Notes |
| --- | --- | --- |
| `value` | string\|null | SVG path `d` shown as existing ink |
| `@change` | callback | `(string $path)`, fires on stroke end; `''` = cleared |
| `clear-token` | int\|string | when it changes, wipe the pad and fire `@change('')` |
| `read-only` | bool | shows ink, ignores touches |
| `stroke-width` | float | on-screen dp/pt, default `2.5` |
| `pen-color` | color | default black |
| `dark-pen-color` | color | default: `pen-color` if set, else white |
| `class` | Tailwind | size, radius, bg, border, parsed like core elements |

Give the pad a **3:1 aspect ratio** (`w-full aspect-[3/1]`). Other ratios work
(ink is letterboxed) but the saved signature will only use the matching part.

## Path format

The emitted and accepted string is an SVG `<path d>` in a fixed **360x120**
coordinate space: absolute commands, one `M x y` per stroke followed by
`L x y` points, 1 decimal place, space separated:

```
M 10.5 20 L 11.2 21.4 L 12 23 M 40 22 L 41 24
```

Render it server-side as `<svg viewBox="0 0 360 120"><path d="..." fill="none" stroke="#000" stroke-width="2"/></svg>`.
`value` additionally accepts relative `m`/`l`, `H`/`V`, comma separators and compact `M10,20L11,21`.
A tap (no movement) is saved as a 0.1 unit segment so it still renders as a dot.

## Tests

```bash
composer install
./vendor/bin/pest
```
