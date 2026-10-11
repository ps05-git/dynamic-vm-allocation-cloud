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
        if (mode.equals("FCFS") || mode.equals("BOTH")) response.put("fcfs", simulateNonPreemptive(workload, Policy.FCFS));
        if (mode.equals("SJF") || mode.equals("BOTH")) response.put("sjf", simulateNonPreemptive(workload, Policy.SJF));
        if (mode.equals("PAPER") || mode.equals("BOTH")) response.put("paper", simulatePaper(workload));
        response.put("note", "FCFS and SJF are baseline scheduling algorithms. Paper policy is a custom educational implementation inspired by deadline- and lease-aware preemption; this is not a CloudSim execution.");
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

    // FCFS and SJF are non-preemptive baselines, scheduled on two parallel VMs.
    private Map<String, Object> simulateNonPreemptive(List<Job> jobs, Policy policy) {
        List<RunJob> all = jobs.stream().map(RunJob::new).toList();
        List<RunJob> waiting = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        int[] vmAvailable = new int[VM_COUNT];
        int completed = 0;
        for (RunJob j : all) {
            j.status = "WAITING";
            waiting.add(j);
        }
        waiting.sort(policy == Policy.FCFS
                ? Comparator.comparingInt((RunJob j) -> j.arrival).thenComparingInt(j -> numericId(j.id))
                : Comparator.comparingInt((RunJob j) -> j.originalExecution).thenComparingInt(j -> j.arrival).thenComparingInt(j -> numericId(j.id)));
        // Dispatch only jobs that have arrived; when all VMs are busy, choose the next completion.
        List<RunJob> remaining = new ArrayList<>(waiting);
        while (!remaining.isEmpty()) {
            int nextArrival = remaining.stream().mapToInt(j -> j.arrival).min().orElse(0);
            int now = Math.max(nextArrival, Arrays.stream(vmAvailable).min().orElse(0));
            int vm = vmAvailable[0] <= vmAvailable[1] ? 0 : 1;
            // Pick from arrived jobs; SJF chooses shortest execution among jobs already available.
            int dispatchTime = now;
            List<RunJob> available = remaining.stream().filter(j -> j.arrival <= dispatchTime).toList();
            if (available.isEmpty()) { now = nextArrival; final int arrivedAt = now; available = remaining.stream().filter(j -> j.arrival <= arrivedAt).toList(); }
            RunJob chosen = available.get(0);
            for (RunJob candidate : available) {
                if (policy == Policy.FCFS) {
                    if (Comparator.comparingInt((RunJob j) -> j.arrival).thenComparingInt(j -> numericId(j.id)).compare(candidate, chosen) < 0) chosen = candidate;
                } else if (Comparator.comparingInt((RunJob j) -> j.originalExecution).thenComparingInt(j -> j.arrival).thenComparingInt(j -> numericId(j.id)).compare(candidate, chosen) < 0) chosen = candidate;
            }
            now = Math.max(chosen.arrival, vmAvailable[vm]);
            // If the chosen job arrives after the selected VM is free, use the other VM if it is available sooner.
            int other = 1 - vm;
            if (vmAvailable[other] <= vmAvailable[vm] && vmAvailable[other] >= chosen.arrival) vm = other;
            int start = Math.max(chosen.arrival, vmAvailable[vm]);
            chosen.start = start; chosen.finish = start + chosen.originalExecution; chosen.remaining = 0;
            chosen.vm = vm + 1; chosen.status = "COMPLETED"; vmAvailable[vm] = chosen.finish;
            events.add(event(start, chosen.id + " started on VM " + chosen.vm + " (" + policy + ")"));
            events.add(event(chosen.finish, chosen.id + " completed on VM " + chosen.vm));
            remaining.remove(chosen); completed++;
        }
        int endTime = all.stream().mapToInt(j -> j.finish).max().orElse(0);
        double avgWaiting = all.stream().mapToInt(j -> j.start - j.arrival).average().orElse(0.0);
        return summary(policy == Policy.FCFS ? "First-Come, First-Served (FCFS)" : "Shortest Job First (SJF)", all, 0, 0, avgWaiting, endTime, events);
    }

    private Map<String, Object> simulatePaper(List<Job> jobs) {
        List<RunJob> all = jobs.stream().map(RunJob::new).toList();
        List<RunJob> pending = new ArrayList<>(all), running = new ArrayList<>(), suspended = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        int preemptions = 0, cancellations = 0, now = 0, safety = 0;
        while (safety++ < 10000) {
            for (RunJob j : running) if (j.remaining > 0) j.remaining--;
            Iterator<RunJob> it = running.iterator();
            while (it.hasNext()) { RunJob j = it.next(); if (j.remaining <= 0) { j.status="COMPLETED"; j.finish=now+1; events.add(event(now+1,j.id+" completed on VM "+j.vm)); it.remove(); } }
            while (running.size() < VM_COUNT && !suspended.isEmpty()) { RunJob resumed=suspended.remove(0); resumed.status="RUNNING"; resumed.vm=running.size()+1; running.add(resumed); events.add(event(now,resumed.id+" resumed on VM "+resumed.vm)); }
            List<RunJob> arriving = new ArrayList<>(); for (RunJob j : pending) if (j.arrival <= now && j.status.equals("QUEUED")) arriving.add(j); pending.removeAll(arriving);
            for (RunJob incoming : arriving) {
                if (running.size() < VM_COUNT) { start(incoming,running,now,events); continue; }
                List<RunJob> candidates = new ArrayList<>();
                for (RunJob active : running) if (!active.lease.equals("NON_PREEMPTABLE") && active.deadline > incoming.deadline) candidates.add(active);
                if (candidates.isEmpty()) { incoming.status="WAITING"; pending.add(incoming); continue; }
                candidates.sort(Comparator.comparingInt((RunJob j)->j.lease.equals("CANCELLABLE")?0:1).thenComparingInt(j->j.originalExecution-j.remaining));
                RunJob victim=candidates.get(0); running.remove(victim); preemptions++;
                if (victim.lease.equals("CANCELLABLE")) { victim.status="CANCELLED"; cancellations++; events.add(event(now,victim.id+" cancelled to admit "+incoming.id)); }
                else { victim.status="SUSPENDED"; suspended.add(victim); events.add(event(now,victim.id+" suspended to admit "+incoming.id)); }
                start(incoming,running,now,events);
            }
            if (pending.isEmpty() && running.isEmpty() && suspended.isEmpty()) break;
            if (running.isEmpty() && !pending.isEmpty()) { int next=pending.stream().filter(j->j.status.equals("QUEUED")).mapToInt(j->j.arrival).min().orElse(now+1); now=next>now?next:now+1; }
            else now++;
        }
        for (RunJob j : all) if (!j.status.equals("COMPLETED") && !j.status.equals("CANCELLED")) j.status="NOT_COMPLETED";
        double avgWaiting=all.stream().filter(j->j.start>=0).mapToInt(j->Math.max(0,j.start-j.arrival)).average().orElse(0.0);
        int endTime=all.stream().mapToInt(j->j.finish).max().orElse(now);
        return summary("Paper-inspired deadline- and lease-aware preemption", all, cancellations, preemptions, avgWaiting, endTime, events);
    }

    private Map<String, Object> summary(String algorithm, List<RunJob> all, int cancellations, int preemptions, double avgWaiting, int endTime, List<Map<String,Object>> events) {
        int completed=(int)all.stream().filter(j->j.status.equals("COMPLETED")).count();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("algorithm",algorithm); result.put("completedJobs",completed); result.put("totalJobs",all.size());
        result.put("cancelledJobs",cancellations); result.put("notCompletedJobs",all.size()-completed-cancellations);
        result.put("preemptions",preemptions); result.put("averageWaitingTime",Math.round(avgWaiting*100.0)/100.0);
        result.put("simulationEndTime",endTime); result.put("jobs",all.stream().map(RunJob::asMap).toList()); result.put("events",events);
        return result;
    }
    private int numericId(String id) { return Integer.parseInt(id.substring(1)); }
    private void start(RunJob job,List<RunJob> running,int now,List<Map<String,Object>> events) { job.status="RUNNING"; if(job.start<0)job.start=now; job.vm=running.size()+1; running.add(job); events.add(event(now,job.id+" started on VM "+job.vm)); }
    private Map<String,Object> event(int time,String message) { return Map.of("time",time,"message",message); }
    private enum Policy { FCFS, SJF }
    private record Job(String id,int arrival,int execution,int deadline,int priority,String lease) {
        Map<String,Object> asMap(){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("arrival",arrival);m.put("execution",execution);m.put("deadline",deadline);m.put("priority",priority);m.put("lease",lease);return m;}
    }
    private static final class RunJob {
        final String id; final int arrival,originalExecution,deadline,priority; final String lease;
        int remaining,start=-1,finish=0,vm=0; String status="QUEUED";
        RunJob(Job j){id=j.id();arrival=j.arrival();originalExecution=j.execution();remaining=j.execution();deadline=j.deadline();priority=j.priority();lease=j.lease();}
        Map<String,Object> asMap(){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("arrival",arrival);m.put("execution",originalExecution);m.put("remaining",remaining);m.put("deadline",deadline);m.put("priority",priority);m.put("lease",lease);m.put("status",status);m.put("start",start);m.put("finish",finish);m.put("vm",vm);return m;}
    }
}
