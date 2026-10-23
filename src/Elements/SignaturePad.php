<?php

namespace PTeal79\SignaturePad\Elements;

use Native\Mobile\Edge\CallbackRegistry;
use Native\Mobile\Edge\Element;

/**
 * Freehand signature pad.
 *
 * Ink is exchanged as an SVG path `d` string in a fixed 360x120 coordinate
 * space (absolute `M x y L x y ...`, one `M` per stroke, 1 decimal place). The
 * native renderers letterbox that space into whatever size the view has.
 *
 * Wire props (consumed by SignaturePadRenderer.kt / PTealSignaturePadRenderer.swift):
 *   value          string  SVG path `d` to show as existing ink (omitted when empty)
 *   on_change      int     callback id; fires on stroke end with the full path
 *                          string ("" = cleared), rides the TEXT_CHANGE event
 *   clear_token    string  when it changes the pad wipes itself and fires on_change("")
 *   read_only      bool
 *   stroke_width   float   on-screen width in dp/pt (default 2.5)
 *   pen_color      string  default black
 *   dark_pen_color string  default white
 *
 * Unlike the Model-3 controls in nativephp/mobile-ui, background, border and
 * radius are NOT locked: size, radius, bg and border come from `class`.
 */
class SignaturePad extends Element
{
    public const DEFAULT_STROKE_WIDTH = 2.5;

    protected string $type = 'signature_pad';

    /** @var array<string, mixed> */
    protected array $padProps = [];

    protected ?string $changeCallback = null;

    public static function make(): static
    {
        return new static;
    }

    public function applyAttributes(array $attrs): void
    {
        if (isset($attrs['value']) && is_scalar($attrs['value'])) {
            $this->value((string) $attrs['value']);
        }

        $token = $attrs['clear-token'] ?? $attrs['clearToken'] ?? null;
        if ($token !== null && is_scalar($token)) {
            $this->clearToken($token);
        }

        $readOnly = $attrs['read-only'] ?? $attrs['readOnly'] ?? null;
        if ($readOnly !== null) {
            $this->readOnly(self::truthy($readOnly));
        }

        $width = $attrs['stroke-width'] ?? $attrs['strokeWidth'] ?? null;
        if ($width !== null && is_numeric($width)) {
            $this->strokeWidth((float) $width);
        }

        $pen = $attrs['pen-color'] ?? $attrs['penColor'] ?? null;
        if (is_string($pen) && $pen !== '') {
            $this->penColor($pen);
        }

        $darkPen = $attrs['dark-pen-color'] ?? $attrs['darkPenColor'] ?? null;
        if (is_string($darkPen) && $darkPen !== '') {
            $this->darkPenColor($darkPen);
        }

        $this->applyA11yAttributes($attrs);
    }

    /** SVG path `d` string to render as existing ink. Null or '' means blank. */
    public function value(?string $path): static
    {
        if ($path === null || $path === '') {
            unset($this->padProps['value']);
        } else {
            $this->padProps['value'] = $path;
        }

        return $this;
    }

    public function clearToken(int|string $token): static
    {
        $this->padProps['clear_token'] = (string) $token;

        return $this;
    }

    public function readOnly(bool $on = true): static
    {
        $this->padProps['read_only'] = $on;

        return $this;
    }

    public function strokeWidth(float $width): static
    {
        $this->padProps['stroke_width'] = $width > 0 ? $width : self::DEFAULT_STROKE_WIDTH;

        return $this;
    }

    public function penColor(string $color): static
    {
        $this->padProps['pen_color'] = $color;

        return $this;
    }

    public function darkPenColor(string $color): static
    {
        $this->padProps['dark_pen_color'] = $color;

        return $this;
    }

    /** Handler receives one string: the full path, or "" when cleared. */
    public function onChange(string $method): static
    {
        $this->changeCallback = $method;

        return $this;
    }

    protected function defaults(): array
    {
        return [
            'stroke_width' => self::DEFAULT_STROKE_WIDTH,
            'read_only' => false,
        ];
    }

    protected function resolveProps(CallbackRegistry $registry): array
    {
        $props = $this->padProps;

        if ($this->changeCallback !== null) {
            $props['on_change'] = $registry->register($this->changeCallback);
        }

        return $props;
    }

    /** A bare `read-only` attribute arrives as true or ''; explicit false-ish strings are false. */
    private static function truthy(mixed $value): bool
    {
        if (is_string($value)) {
            return ! in_array(strtolower(trim($value)), ['false', '0', 'no', 'off'], true);
        }

        return (bool) $value;
    }
}
