/**
 * Cross-cutting infrastructure: security, outbox job runner, error handling.
 * Open module: every other module may use any of its types.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.fulfillops.shared;

import org.springframework.modulith.ApplicationModule;
