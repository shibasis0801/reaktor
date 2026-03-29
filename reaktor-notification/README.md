# reaktor-notification

`reaktor-notification` is the shared notification adapter surface.

## Current purpose

- provide a stable abstraction for notification delivery and registration
- keep notification integration out of product code where possible

## Status

This module is still intentionally thin. It exists as the shared seam for platform-specific notification implementations.
