// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/settings")
class DemoSettingsController {

    private final DemoSettings settings;

    DemoSettingsController(DemoSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    DemoSettings.Snapshot get() {
        return settings.snapshot();
    }

    @PutMapping
    DemoSettings.Snapshot put(@RequestBody DemoSettings.Snapshot next) {
        settings.update(next);
        return settings.snapshot();
    }
}
