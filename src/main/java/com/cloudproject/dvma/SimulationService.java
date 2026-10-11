package com.cloudproject.dvma;

import java.util.*;
import org.cloudsimplus.core.CloudSim;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.cloudsimplus.schedulers.vm.VmSchedulerSpaceShared;
import org.springframework.stereotype.Service;

@Service
public class SimulationService {
    private static final int HOST_COUNT = 2;
    private static final int VM_COUNT = 2;
    private static final long MIPS = 1000;

    public Map<String, Object> run(String mode, long seed, int processCount) {
        List<Job> workload = generateWorkload(seed, processCount);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("project", "Dynamic VM Allocation with CloudSim Plus");
        response.put("engine", "CloudSim Plus 8.0.0");
        response.put("mode", mode);
        response.put("seed", seed);
        response.put("processCount", processCount);
        response.put("hosts", HOST_COUNT);
        response.put("vms", VM_COUNT);
        response.put("workload", workload.stream().map(Job::asMap).toList());
        if (mode.equals("FCFS") || mode.equals("BOTH")) response.put("fcfs", simulateCloudSim(workload, Policy.FCFS));
        if (mode.equals("SJF") || mode.equals("BOTH")) response.put("sjf", simulateCloudSim(workload, Policy.SJF));
        if (mode.equals("PAPER") || mode.equals("BOTH")) response.put("paper", simulateCloudSim(workload, Policy.PAPER));
        response.put("note", "CloudSim Plus creates the datacenter, hosts, VMs and Cloudlets and executes each workload. FCFS, SJF and paper-inspired ordering policies are compared on the same generated workload. The paper-inspired mode is a simplified policy model, not a validated reproduction of the paper's full preemption algorithm.");
        return response;
    }

    private List<Job> generateWorkload(long seed, int count) {
        Random r = new Random(seed);
        String[] leases = {"SUSPENDABLE", "CANCELLABLE", "NON_PREEMPTABLE"};
        List<Job> jobs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int arrival = i <= 2 ? 0 : r.nextInt(Math.max(3, count * 2));
            int execution = 4 + r.nextInt(17);
            int deadline = arrival + execution + 5 + r.nextInt(28);
            int priority = 1 + r.nextInt(5);
            jobs.add(new Job("P" + i, arrival, execution, deadline, priority, leases[r.nextInt(leases.length)]));
        }
        jobs.sort(Comparator.comparingInt(Job::arrival).thenComparingInt(j -> idNumber(j.id)));
        return jobs;
    }

    private Map<String, Object> simulateCloudSim(List<Job> source, Policy policy) {
        // A fresh CloudSim simulation is created for each algorithm so the same workload is compared fairly.
        CloudSim simulation = new CloudSim();
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < HOST_COUNT; h++) {
            List<Pe> pes = List.of(new PeSimple(MIPS), new PeSimple(MIPS));
            Host host = new HostSimple(8192, 100_000, 1_000_000, pes);
            host.setVmScheduler(new VmSchedulerSpaceShared());
            hosts.add(host);
        }
        Datacenter datacenter = new DatacenterSimple(simulation, hosts, new VmAllocationPolicySimple());
        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < VM_COUNT; i++) {
            Vm vm = new VmSimple(MIPS, 1);
            vm.setRam(2048).setBw(10_000).setSize(10_000);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }
        List<Job> ordered = new ArrayList<>(source);
        switch (policy) {
            case FCFS -> ordered.sort(Comparator.comparingInt(Job::arrival).thenComparingInt(j -> idNumber(j.id)));
            case SJF -> ordered.sort(Comparator.comparingInt(Job::execution).thenComparingInt(Job::arrival).thenComparingInt(j -> idNumber(j.id)));
            case PAPER -> ordered.sort(Comparator.comparingInt(Job::deadline).thenComparingInt(Job::priority).thenComparingInt(Job::arrival));
        }
        List<Cloudlet> cloudlets = new ArrayList<>();
        Map<Long, Job> jobByCloudletId = new HashMap<>();
        for (Job job : ordered) {
            Cloudlet cloudlet = new CloudletSimple((long) job.execution * MIPS, 1);
            cloudlet.setFileSize(300).setOutputSize(300);
            cloudlet.setUtilizationModelCpu(new UtilizationModelFull());
            cloudlet.setSubmissionDelay(job.arrival);
            cloudlets.add(cloudlet);
            jobByCloudletId.put(cloudlet.getId(), job);
        }
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);
        simulation.start();

        List<Cloudlet> finished = new ArrayList<>(broker.getCloudletFinishedList());
        Set<Long> finishedIds = new HashSet<>();
        List<Map<String, Object>> resultJobs = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        double waitingTotal = 0;
        int completed = 0;
        for (Cloudlet cloudlet : finished) {
            Job job = jobByCloudletId.get(cloudlet.getId());
            if (job == null) continue;
            finishedIds.add(cloudlet.getId());
            String status = cloudlet.getStatus().toString().equalsIgnoreCase("SUCCESS") ? "COMPLETED" : cloudlet.getStatus().toString();
            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getFinishTime();
            double waiting = Math.max(0, cloudlet.getWaitingTime());
            waitingTotal += waiting;
            if (status.equals("COMPLETED")) completed++;
            Map<String, Object> row = job.asMap();
            row.put("status", status);
            row.put("start", Math.round(start * 100.0) / 100.0);
            row.put("finish", Math.round(finish * 100.0) / 100.0);
            row.put("vm", cloudlet.getVm() == null ? "—" : "VM-" + (cloudlet.getVm().getId() + 1));
            row.put("waitingTime", Math.round(waiting * 100.0) / 100.0);
            resultJobs.add(row);
            events.add(event(job.arrival, job.id + " arrived in the workload"));
            events.add(event(start, job.id + " started on VM " + row.get("vm")));
            events.add(event(finish, job.id + " completed"));
        }
        for (Cloudlet cloudlet : broker.getCloudletSubmittedList()) {
            if (!finishedIds.contains(cloudlet.getId())) {
                Job job = jobByCloudletId.get(cloudlet.getId());
                if (job != null) {
                    Map<String, Object> row = job.asMap(); row.put("status", "NOT_COMPLETED"); row.put("start", -1); row.put("finish", 0); row.put("vm", "—"); row.put("waitingTime", 0);
                    resultJobs.add(row);
                }
            }
        }
        resultJobs.sort(Comparator.comparingInt(j -> idNumber((String) j.get("id"))));
        events.sort(Comparator.comparingDouble(e -> ((Number)e.get("time")).doubleValue()));
        double end = finished.stream().mapToDouble(Cloudlet::getFinishTime).max().orElse(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", switch (policy) { case FCFS -> "FCFS (CloudSim Plus Cloudlets)"; case SJF -> "SJF (CloudSim Plus Cloudlets)"; case PAPER -> "Paper-inspired deadline/priority ordering (CloudSim Plus Cloudlets)"; });
        result.put("totalJobs", source.size()); result.put("completedJobs", completed); result.put("cancelledJobs", 0); result.put("notCompletedJobs", source.size() - completed);
        result.put("preemptions", 0); result.put("averageWaitingTime", Math.round((waitingTotal / Math.max(1, completed)) * 100.0) / 100.0);
        result.put("simulationEndTime", Math.round(end * 100.0) / 100.0); result.put("jobs", resultJobs); result.put("events", events);
        return result;
    }

    private Map<String, Object> event(double time, String message) { return Map.of("time", Math.round(time * 100.0) / 100.0, "message", message); }
    private static int idNumber(String id) { return Integer.parseInt(id.substring(1)); }
    private enum Policy { FCFS, SJF, PAPER }
    private record Job(String id, int arrival, int execution, int deadline, int priority, String lease) {
        Map<String, Object> asMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("arrival", arrival); m.put("execution", execution); m.put("deadline", deadline); m.put("priority", priority); m.put("lease", lease); return m; }
    }
}
