package com.cloudproject.dvma;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "application", "Dynamic VM Allocation", "version", "1.0.0");
    }

    @GetMapping("/simulation/run")
    public Map<String, Object> run(
            @RequestParam(defaultValue = "BOTH") String mode,
            @RequestParam(defaultValue = "1") long seed,
            @RequestParam(defaultValue = "12") int processes) {
        String normalized = mode.toUpperCase();
        if (!normalized.equals("BOTH") && !normalized.equals("PAPER") && !normalized.equals("FCFS") && !normalized.equals("SJF")) {
            throw new IllegalArgumentException("mode must be BOTH, PAPER, FCFS, or SJF");
        }
        if (processes < 2 || processes > 50) {
            throw new IllegalArgumentException("processes must be between 2 and 50");
        }
        return simulationService.run(normalized, seed, processes);
    }
}
