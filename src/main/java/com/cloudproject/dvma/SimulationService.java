package com.cloudproject.dvma;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SimulationService {
    private static final int VM_COUNT = 2;

    public Map<String, Object> run(String mode, long seed) {
        List<Job> workload = generateWorkload(seed);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project", "Dynamic VM Allocation");
        response.put("mode", mode);
        response.put("seed", seed);
        response.put("hosts", 2);
        response.put("vms", VM_COUNT);
        response.put("workload", workload.stream().map(Job::asMap).toList());
        if (mode.equals("PAPER") || mode.equals("BOTH")) {
            response.put("paper", simulate(workload, Policy.PAPER));
        }
        if (mode.equals("MODIFIED") || mode.equals("BOTH")) {
            response.put("modified", simulate(workload, Policy.MODIFIED));
        }
        response.put("note", "Custom educational discrete-event simulation inspired by deadline- and lease-aware preemption; not a CloudSim Plus execution.");
        return response;
    }

    private List<Job> generateWorkload(long seed) {
        Random r = new Random(seed);
        List<Job> jobs = new ArrayList<>();
        String[] leases = {"SUSPENDABLE", "CANCELLABLE", "NON_PREEMPTABLE"};
        for (int i = 1; i <= 12; i++) {
            int arrival = (i <= 2) ? 0 : r.nextInt(24);
            int execution = 4 + r.nextInt(17);
            int deadline = arrival + execution + 5 + r.nextInt(28);
            int priority = 1 + r.nextInt(5);
            String lease = leases[r.nextInt(leases.length)];
            jobs.add(new Job("J" + i, arrival, execution, deadline, priority, lease));
        }
        jobs.sort(Comparator.comparingInt(Job::arrival).thenComparing(Job::id));
        return jobs;
    }

    private Map<String, Object> simulate(List<Job> jobs, Policy policy) {
        // A compact, deterministic time-step simulator suitable for demonstrating policy behavior.
        List<RunJob> all = jobs.stream().map(RunJob::new).toList();
        List<RunJob> pending = new ArrayList<>(all);
        List<RunJob> running = new ArrayList<>();
        List<RunJob> suspended = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        int preemptions = 0;
        int cancellations = 0;
        int now = 0;
        int safety = 0;

        while (safety++ < 10000) {
            for (RunJob j : running) {
                if (j.remaining > 0) j.remaining--;
            }
            Iterator<RunJob> it = running.iterator();
            while (it.hasNext()) {
                RunJob j = it.next();
                if (j.remaining <= 0) {
                    j.status = "COMPLETED";
                    j.finish = now + 1;
                    events.add(event(now + 1, j.id + " completed on VM " + j.vm));
                    it.remove();
                }
            }
            while (running.size() < VM_COUNT && !suspended.isEmpty()) {
                RunJob resumed = suspended.remove(0);
                resumed.status = "RUNNING";
                resumed.vm = running.size();
                running.add(resumed);
                events.add(event(now, resumed.id + " resumed"));
            }
            List<RunJob> arriving = new ArrayList<>();
            for (RunJob j : pending) if (j.arrival <= now) arriving.add(j);
            pending.removeAll(arriving);

            for (RunJob incoming : arriving) {
                if (running.size() < VM_COUNT) {
                    start(incoming, running, now, events);
                    continue;
                }
                List<RunJob> candidates = new ArrayList<>();
                for (RunJob active : running) {
                    if (active.lease.equals("NON_PREEMPTABLE")) continue;
                    if (active.deadline <= incoming.deadline) continue;
                    candidates.add(active);
                }
                if (candidates.isEmpty()) {
                    incoming.status = "WAITING";
                    pending.add(incoming);
                    continue;
                }

                RunJob victim;
                if (policy == Policy.PAPER) {
                    // Paper-inspired: prefer cancellable over suspendable; then choose least-progressed.
                    candidates.sort(Comparator
                            .comparingInt((RunJob j) -> j.lease.equals("CANCELLABLE") ? 0 : 1)
                            .thenComparingInt(j -> j.originalExecution - j.remaining));
                    victim = candidates.get(0);
                } else {
                    // Modified comparison policy: select the candidate with the least remaining execution.
                    candidates.sort(Comparator.comparingInt(j -> j.remaining));
                    victim = candidates.get(0);
                }
                running.remove(victim);
                preemptions++;
                if (victim.lease.equals("CANCELLABLE")) {
                    victim.status = "CANCELLED";
                    cancellations++;
                    events.add(event(now, victim.id + " cancelled to admit " + incoming.id));
                } else {
                    victim.status = "SUSPENDED";
                    suspended.add(victim);
                    events.add(event(now, victim.id + " suspended to admit " + incoming.id));
                }
                start(incoming, running, now, events);
            }

            if (pending.isEmpty() && running.isEmpty() && suspended.isEmpty()) break;
            if (running.isEmpty() && !pending.isEmpty()) {
                int nextArrival = pending.stream().mapToInt(j -> j.arrival).min().orElse(now + 1);
                if (nextArrival > now) now = nextArrival;
                else now++;
            } else {
                now++;
            }
        }

        for (RunJob j : all) {
            if (!j.status.equals("COMPLETED") && !j.status.equals("CANCELLED")) {
                j.status = "NOT_COMPLETED";
            }
        }
        int completed = (int) all.stream().filter(j -> j.status.equals("COMPLETED")).count();
        double avgWaiting = all.stream().filter(j -> j.status.equals("COMPLETED"))
                .mapToInt(j -> Math.max(0, j.start - j.arrival)).average().orElse(0.0);
        int endTime = all.stream().mapToInt(j -> j.finish).max().orElse(now);
        List<Map<String, Object>> jobResults = all.stream().map(RunJob::asMap).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("algorithm", policy == Policy.PAPER ? "Paper-inspired lease-aware preemption" : "Modified minimum-remaining-time preemption");
        summary.put("completedJobs", completed);
        summary.put("totalJobs", all.size());
        summary.put("cancelledJobs", cancellations);
        summary.put("notCompletedJobs", all.size() - completed - cancellations);
        summary.put("preemptions", preemptions);
        summary.put("averageWaitingTime", Math.round(avgWaiting * 100.0) / 100.0);
        summary.put("simulationEndTime", endTime);
        summary.put("jobs", jobResults);
        summary.put("events", events);
        return summary;
    }

    private void start(RunJob job, List<RunJob> running, int now, List<Map<String, Object>> events) {
        job.status = "RUNNING";
        job.start = job.start < 0 ? now : job.start;
        job.vm = running.size() + 1;
        running.add(job);
        events.add(event(now, job.id + " started on VM " + job.vm));
    }

    private Map<String, Object> event(int time, String message) {
        return Map.of("time", time, "message", message);
    }

    private enum Policy { PAPER, MODIFIED }

    private record Job(String id, int arrival, int execution, int deadline, int priority, String lease) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("arrival", arrival); m.put("execution", execution);
            m.put("deadline", deadline); m.put("priority", priority); m.put("lease", lease);
            return m;
        }
    }

    private static final class RunJob {
        final String id;
        final int arrival, originalExecution, deadline, priority;
        final String lease;
        int remaining, start = -1, finish = 0, vm = 0;
        String status = "QUEUED";

        RunJob(Job j) {
            id = j.id(); arrival = j.arrival(); originalExecution = j.execution();
            remaining = j.execution(); deadline = j.deadline(); priority = j.priority(); lease = j.lease();
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("arrival", arrival); m.put("execution", originalExecution);
            m.put("remaining", remaining); m.put("deadline", deadline); m.put("priority", priority);
            m.put("lease", lease); m.put("status", status); m.put("start", start); m.put("finish", finish); m.put("vm", vm);
            return m;
        }
    }
}
