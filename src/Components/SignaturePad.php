<?php

namespace PTeal79\SignaturePad\Components;

use Native\Mobile\Edge\Components\Native\NativeBladeComponent;

/**
 * Blade tag: <native:signature-pad />
 */
class SignaturePad extends NativeBladeComponent
{
    protected bool $isSelfClosing = true;

    protected function elementType(): string
    {
        return 'signature_pad';
    }
}
