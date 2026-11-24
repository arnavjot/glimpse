package com.arnavjot.glimpse.server.experiments;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.arnavjot.glimpse.server.common.AdminAuth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Admin (X-Admin-Token): list experiments, change the split or switch one off, read results. */
@RestController
@Validated
public class ExperimentController {

    public record ExperimentUpdate(Boolean enabled, @Min(0) @Max(100) Integer treatmentPercent) {}

    private final ExperimentService experiments;
    private final ExperimentReport reports;
    private final AdminAuth admin;

    public ExperimentController(ExperimentService experiments, ExperimentReport reports, AdminAuth admin) {
        this.experiments = experiments;
        this.reports = reports;
        this.admin = admin;
    }

    @GetMapping("/v1/admin/experiments")
    public List<ExperimentService.Experiment> list(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        return experiments.all();
    }

    /** Changing the split only affects devices not seen yet: assignments are sticky. */
    @PutMapping("/v1/admin/experiments/{key}")
    public ExperimentService.Experiment update(@PathVariable @Size(max = 50) String key,
                                               @RequestBody @Valid ExperimentUpdate change,
                                               @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        return experiments.update(key, change.enabled(), change.treatmentPercent());
    }

    @GetMapping("/v1/admin/experiments/{key}/report")
    public ExperimentReport.Report report(@PathVariable @Size(max = 50) String key,
                                          @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        return reports.build(key);
    }
}
