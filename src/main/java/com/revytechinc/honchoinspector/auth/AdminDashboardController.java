package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only aggregate dashboard. Pure local SQL aggregates plus a parallel Honcho fan-out (5s per-profile timeout, graceful degradation).")
public class AdminDashboardController {

    private final AdminDashboardService dashboard;

    public AdminDashboardController(AdminDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overview")
    @Operation(summary = "Local aggregates: user / profile / audit / session counts, growth over 7d and 30d")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(dashboard.overview());
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Per-user drilldown: profile list, recent sessions, last 20 audit events")
    public ResponseEntity<?> userDrilldown(@PathVariable String id) {
        var m = dashboard.userDrilldown(id);
        if (m == null) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        return ResponseEntity.ok(m);
    }

    @GetMapping("/honcho")
    @Operation(summary = "All Honcho profiles with reachability probe (parallel, 5s per profile)")
    public ResponseEntity<?> honchoList() {
        return ResponseEntity.ok(dashboard.honchoList());
    }

    @GetMapping("/honcho/{profileId}")
    @Operation(summary = "Per-profile Honcho drilldown: queue status + workspace info")
    public ResponseEntity<?> honchoDrilldown(@PathVariable String profileId) {
        var m = dashboard.honchoDrilldown(profileId);
        if (m == null) return ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
        return ResponseEntity.ok(m);
    }
}
