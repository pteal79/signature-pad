<?php

use PTeal79\SignaturePad\Elements\SignaturePad;
use Native\Mobile\Edge\CallbackRegistry;
use Native\Mobile\Edge\ElementRegistry;
use Native\Mobile\Edge\NativeElementCollector;
use Native\Mobile\Edge\TailwindParser;

beforeEach(function () {
    NativeElementCollector::reset();
    TailwindParser::clearCache();
    ElementRegistry::reset();
    ElementRegistry::register('signature_pad', SignaturePad::class);
});

afterEach(function () {
    NativeElementCollector::reset();
    ElementRegistry::reset();
});

function collectPad(array $attrs, ?CallbackRegistry $registry = null): array
{
    NativeElementCollector::leaf('signature_pad', $attrs);

    return NativeElementCollector::collect()->toArray($registry ?? new CallbackRegistry);
}

it('serializes as a signature_pad node with default props', function () {
    $node = collectPad([]);

    expect($node['type'])->toBe('signature_pad');
    expect($node['props']['stroke_width'])->toBe(2.5);
    expect($node['props']['read_only'])->toBeFalse();
    expect($node['props'])->not->toHaveKeys(['value', 'on_change', 'clear_token']);
});

it('passes the value path through untouched', function () {
    $path = 'M 10.5 20 L 11.2 21.4 L 12 23';

    expect(collectPad(['value' => $path])['props']['value'])->toBe($path);
});

it('omits value when null or empty', function () {
    expect(collectPad(['value' => ''])['props'])->not->toHaveKey('value');
    expect(collectPad(['value' => null])['props'])->not->toHaveKey('value');
});

it('registers the @change handler and wires on_change', function () {
    $registry = new CallbackRegistry;
    $node = collectPad(['_change' => 'onSignature'], $registry);

    $id = $node['props']['on_change'];

    expect($id)->toBeInt()->toBeGreaterThan(0);
    expect($registry->resolve($id)['method'])->toBe('onSignature');
});

it('casts clear-token to a string, from int or string', function () {
    expect(collectPad(['clear-token' => 3])['props']['clear_token'])->toBe('3');
    expect(collectPad(['clear-token' => 'abc'])['props']['clear_token'])->toBe('abc');
    expect(collectPad(['clearToken' => 0])['props']['clear_token'])->toBe('0');
});

it('parses read-only as a boolean', function () {
    expect(collectPad(['read-only' => true])['props']['read_only'])->toBeTrue();
    expect(collectPad(['read-only' => ''])['props']['read_only'])->toBeTrue();
    expect(collectPad(['read-only' => 'false'])['props']['read_only'])->toBeFalse();
    expect(collectPad(['read-only' => false])['props']['read_only'])->toBeFalse();
});

it('accepts stroke width and pen colors', function () {
    $props = collectPad([
        'stroke-width' => '3.5',
        'pen-color' => '#111827',
        'dark-pen-color' => '#F9FAFB',
    ])['props'];

    expect($props['stroke_width'])->toBe(3.5);
    expect($props['pen_color'])->toBe('#111827');
    expect($props['dark_pen_color'])->toBe('#F9FAFB');
});

it('falls back to the default stroke width for non-positive values', function () {
    expect(collectPad(['stroke-width' => 0])['props']['stroke_width'])->toBe(2.5);
    expect(collectPad(['stroke-width' => -4])['props']['stroke_width'])->toBe(2.5);
});

it('keeps tailwind sizing, radius, background and border from the class attribute', function () {
    $node = collectPad(['class' => 'w-full aspect-[3/1] rounded-xl bg-white']);

    expect($node['layout']['aspect_ratio'])->toBe(3.0);
    expect($node['style'])->toHaveKeys(['bg_color', 'border_radius']);
});

it('supports the fluent API', function () {
    $node = SignaturePad::make()
        ->value('M 1 2 L 3 4')
        ->clearToken(7)
        ->readOnly()
        ->strokeWidth(4)
        ->penColor('#000000')
        ->onChange('saved')
        ->toArray($registry = new CallbackRegistry);

    expect($node['props'])->toMatchArray([
        'value' => 'M 1 2 L 3 4',
        'clear_token' => '7',
        'read_only' => true,
        'stroke_width' => 4.0,
        'pen_color' => '#000000',
    ]);
    expect($registry->resolve($node['props']['on_change'])['method'])->toBe('saved');
});
