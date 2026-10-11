                    List<RunJob> candidates = running.stream()
                            .filter(r -> !r.job.lease.equals("NON_PREEMPTABLE"))
                            .filter(r -> incoming.job.deadline < r.job.deadline)
                            .sorted(Comparator
                                    .comparingInt((RunJob r) -> r.job.lease.equals("CANCELLABLE") ? 0 : 1)
                                    .thenComparingDouble(r -> policy == Policy.PAPER ? -r.remaining : r.remaining)
                                    .thenComparingInt(r -> r.job.arrival)
                                    .thenComparingInt(r -> idNumber(r.job.id)))
                            .toList();
                    if (candidates.isEmpty()) break;

                    RunJob victim = candidates.get(0);
                    waiting.remove(incoming);
                    running.remove(victim);
                    preemptions++;
                    events.add(event(now, incoming.job.id + " preempts " + victim.job.id));

                    if (victim.job.lease.equals("CANCELLABLE")) {
                        victim.status = "CANCELLED";
                        victim.finish = now;
                        cancelled++;
                        end = Math.max(end, now);
                        events.add(event(now, victim.job.id + " cancelled"));
                    } else {
                        // Remaining execution is already maintained continuously by the event clock.
                        victim.status = "SUSPENDED";
                        victim.readyAt = now;
                        suspended.add(victim);
                        events.add(event(now, victim.job.id + " suspended"));
                    }

                    incoming.totalWaiting += Math.max(0, now - incoming.readyAt);
                    startJob(incoming, victim.vm, now, running, events);
                }
            }

            // Allocate free VMs. PAPER/MODIFIED retain FCFS dispatch.
            while (running.size() < VM_COUNT && !waiting.isEmpty()) {
                RunJob chosen;
                if (policy == Policy.SJF) {
                    chosen = waiting.stream().min(Comparator
                            .comparingDouble((RunJob r) -> r.remaining)
                            .thenComparingInt(r -> r.job.arrival)
                            .thenComparingInt(r -> idNumber(r.job.id))).orElseThrow();
                } else {
                    chosen = waiting.stream().min(Comparator
                            .comparingInt((RunJob r) -> r.job.arrival)
                            .thenComparingInt(r -> idNumber(r.job.id))).orElseThrow();
                }
                waiting.remove(chosen);
                chosen.totalWaiting += Math.max(0, now - chosen.readyAt);
                startJob(chosen, firstFreeVm(running), now, running, events);
            }

            // Resume suspended work only after queued arrivals have had a chance to use free VMs.
            while (running.size() < VM_COUNT && !suspended.isEmpty()) {
                RunJob resumed = suspended.stream().min(Comparator
                        .comparingInt((RunJob r) -> r.job.arrival)
                        .thenComparingInt(r -> idNumber(r.job.id))).orElseThrow();
                suspended.remove(resumed);
                resumed.totalWaiting += Math.max(0, now - resumed.readyAt);
                startJob(resumed, firstFreeVm(running), now, running, events);
                events.add(event(now, resumed.job.id + " resumed"));
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RunJob r : all) {
            Map<String, Object> row = r.job.asMap();
            row.put("status", r.status);
            row.put("start", r.firstStart < 0 ? -1 : round2(r.firstStart));
            row.put("finish", r.finish < 0 ? 0 : round2(r.finish));
            row.put("vm", r.vm < 0 ? "—" : "VM-" + (r.vm + 1));
            row.put("waitingTime", round2(r.totalWaiting));
            row.put("remainingExecution", round2(r.remaining));
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt(r -> idNumber((String) r.get("id"))));
        events.sort(Comparator.comparingDouble(e -> ((Number) e.get("time")).doubleValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", switch (policy) {
            case FCFS -> "FCFS";
            case SJF -> "SJF";
            case PAPER -> "Paper-inspired lease-aware preemption (max remaining)";
            case MODIFIED -> "Modified lease-aware preemption (min remaining)";
        });
        result.put("totalJobs", workload.size());
        result.put("completedJobs", completed);
        result.put("cancelledJobs", cancelled);
        result.put("notCompletedJobs", workload.size() - completed - cancelled);
        result.put("preemptions", preemptions);
        result.put("averageWaitingTime", round2(waitingSum / Math.max(1, completed)));
        result.put("simulationEndTime", round2(end));
        result.put("jobs", rows);
        result.put("events", events);
        return result;
    }

    private RunJob findRunJob(List<RunJob> all, String id) {
        return all.stream().filter(r -> r.job.id.equals(id)).findFirst().orElseThrow();
    }

    private void startJob(RunJob job, int vm, double now, List<RunJob> running,
                          List<Map<String, Object>> events) {
        job.vm = vm;
        if (job.firstStart < 0) job.firstStart = now;
        job.status = "RUNNING";
        running.add(job);
        events.add(event(now, job.job.id + " started on VM-" + (vm + 1)));
    }

    private int firstFreeVm(List<RunJob> running) {
        for (int vm = 0; vm < VM_COUNT; vm++) {
            final int candidate = vm;
            if (running.stream().noneMatch(r -> r.vm == candidate)) return vm;
        }
        throw new IllegalStateException("No free VM available");
    }

    private Map<String, Object> event(double time, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", round2(time));
        m.put("message", message);
        return m;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static int idNumber(String id) { return Integer.parseInt(id.substring(1)); }

    private enum Policy { FCFS, SJF, PAPER, MODIFIED }

    private record Job(String id, int arrival, int execution, int deadline, int priority, String lease) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("arrival", arrival);
            m.put("execution", execution);
            m.put("deadline", deadline);
            m.put("priority", priority);
            m.put("lease", lease);
            return m;
        }
    }

    private static class RunJob {
        final Job job;
        double remaining;
        double firstStart = -1;
        double finish = -1;
        double totalWaiting = 0;
        double readyAt = 0;
        int vm = -1;
        String status = "WAITING";
        RunJob(Job job) { this.job = job; this.remaining = job.execution; this.readyAt = job.arrival; }
    }
}
