<?php

namespace PTeal79\SignaturePad;

use Illuminate\Support\ServiceProvider;

/**
 * The `signature_pad` element and its `<native:signature-pad>` Blade tag are
 * registered by NativePHP core from this package's `nativephp.json`
 * `components` entry, so there is nothing to bind here. The provider exists so
 * Laravel package discovery loads the package (and so the plugin registry can
 * resolve it by service provider).
 *
 * `@change` is a core event name, so no `registerElementEvents()` call is needed.
 */
class SignaturePadServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        //
    }

    public function boot(): void
    {
        //
    }
}
