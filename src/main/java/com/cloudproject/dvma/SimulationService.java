package com.cloudproject.dvma;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import org.springframework.stereotype.Service;

@Service
public class SimulationService {

    private static final int HOST_COUNT = 2;
    private static final int VM_COUNT = 2;

    private static final long HOST_MIPS = 1000;
    private static final long VM_MIPS = 1000;

    private static final int HOST_PES = 2;
    private static final int VM_PES = 1;

    private static final int MAX_PROCESSES = 50;

    /**
     * Runs the requested scheduling algorithm or compares all algorithms.
     */
    public Map<String, Object> run(
            String mode,
            long seed,
            int processCount) {

        if (mode == null) {
            mode = "BOTH";
        }

        mode = mode.toUpperCase();

        if (processCount < 2 || processCount > MAX_PROCESSES) {
            throw new IllegalArgumentException(
                    "processes must be between 2 and 50");
        }

        if (!mode.equals("BOTH")
                && !mode.equals("FCFS")
                && !mode.equals("SJF")
                && !mode.equals("PAPER")) {

            throw new IllegalArgumentException(
                    "mode must be BOTH, FCFS, SJF, or PAPER");
        }

        /*
         * Generate the workload once.
         * Each algorithm receives the same workload.
         */
        List<Job> workload = generateWorkload(seed, processCount);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put(
                "project",
                "Dynamic VM Allocation with CloudSim Plus");

        response.put("engine", "CloudSim Plus 8.0.0");
        response.put("mode", mode);
        response.put("seed", seed);
        response.put("processCount", processCount);
        response.put("hosts", HOST_COUNT);
        response.put("vms", VM_COUNT);

        response.put(
                "workload",
                workload.stream()
                        .map(Job::asMap)
                        .toList());

        if (mode.equals("BOTH") || mode.equals("FCFS")) {
            response.put(
                    "fcfs",
                    simulateCloudSim(workload, Policy.FCFS));
        }

        if (mode.equals("BOTH") || mode.equals("SJF")) {
            response.put(
                    "sjf",
                    simulateCloudSim(workload, Policy.SJF));
        }

        if (mode.equals("BOTH") || mode.equals("PAPER")) {
            response.put(
                    "paper",
                    simulateCloudSim(workload, Policy.PAPER));
        }

        response.put(
                "note",
                "CloudSim Plus executes the datacenter, hosts, VMs "
                + "and Cloudlets. FCFS, SJF and PAPER use different "
                + "submission ordering policies. PAPER is a simplified "
                + "deadline/priority policy and does not implement the "
                + "research paper's complete lease-aware preemption.");

        return response;
    }

    /**
     * Creates a reproducible workload.
     */
    private List<Job> generateWorkload(long seed, int count) {

        Random random = new Random(seed);

        String[] leases = {
                "SUSPENDABLE",
                "CANCELLABLE",
                "NON_PREEMPTABLE"
        };

        List<Job> jobs = new ArrayList<>();

        for (int i = 1; i <= count; i++) {

            int arrival;

            if (i <= 2) {
                arrival = 0;
            } else {
                arrival = random.nextInt(Math.max(3, count * 2));
            }

            int execution = 4 + random.nextInt(17);

            int deadline =
                    arrival + execution + 5 + random.nextInt(28);

            int priority = 1 + random.nextInt(5);

            String lease = leases[random.nextInt(leases.length)];

            jobs.add(new Job(
                    "P" + i,
                    arrival,
                    execution,
                    deadline,
                    priority,
                    lease));
        }

        jobs.sort(
                Comparator.comparingInt(Job::arrival)
                        .thenComparingInt(
                                job -> idNumber(job.id())));

        return jobs;
    }

    /**
     * Runs one CloudSim Plus simulation.
     */
    private Map<String, Object> simulateCloudSim(
            List<Job> source,
            Policy policy) {

        /*
         * Create a fresh simulation for each algorithm.
         */
        CloudSimPlus simulation = new CloudSimPlus();

        /*
         * 1. Create physical hosts.
         */
        List<Host> hosts = new ArrayList<>();

        for (int i = 0; i < HOST_COUNT; i++) {

            List<Pe> processingElements = new ArrayList<>();

            for (int p = 0; p < HOST_PES; p++) {
                processingElements.add(
                        new PeSimple(HOST_MIPS));
            }

            Host host = new HostSimple(
                    8192,
                    100000,
                    1000000,
                    processingElements);

            host.setVmScheduler(
                    new VmSchedulerSpaceShared());

            hosts.add(host);
        }

        /*
         * 2. Create the datacenter.
         */
        Datacenter datacenter = new DatacenterSimple(
                simulation,
                hosts,
                new VmAllocationPolicySimple());

        /*
         * 3. Create the broker.
         */
        DatacenterBroker broker =
                new DatacenterBrokerSimple(simulation);

        /*
         * 4. Create virtual machines.
         */
        List<Vm> vms = new ArrayList<>();

        for (int i = 0; i < VM_COUNT; i++) {

            Vm vm = new VmSimple(VM_MIPS, VM_PES);

            vm.setRam(2048)
                    .setBw(10000)
                    .setSize(10000);

            vm.setCloudletScheduler(
                    new CloudletSchedulerSpaceShared());

            vms.add(vm);
        }

        /*
         * 5. Apply the selected submission ordering.
         *
         * Submission ordering is not the same as a complete
         * preemptive scheduling algorithm.
         */
        List<Job> ordered = new ArrayList<>(source);

        switch (policy) {

            case FCFS:
                ordered.sort(
                        Comparator.comparingInt(Job::arrival)
                                .thenComparingInt(
                                        job -> idNumber(job.id())));
                break;

            case SJF:
                ordered.sort(
                        Comparator.comparingInt(Job::arrival)
                                .thenComparingInt(Job::execution)
                                .thenComparingInt(
                                        job -> idNumber(job.id())));
                break;

            case PAPER:
                ordered.sort(
                        Comparator.comparingInt(Job::arrival)
                                .thenComparingInt(Job::deadline)
                                .thenComparingInt(Job::priority)
                                .thenComparingInt(
                                        job -> idNumber(job.id())));
                break;
        }

        /*
         * 6. Create Cloudlets from the ordered workload.
         */
        List<Cloudlet> cloudlets = new ArrayList<>();

        Map<Long, Job> jobByCloudletId = new HashMap<>();

        for (Job job : ordered) {

            long cloudletId = idNumber(job.id());

            long cloudletLength =
                    (long) job.execution() * VM_MIPS;

            Cloudlet cloudlet = new CloudletSimple(
                    cloudletId,
                    cloudletLength,
                    1);

            cloudlet.setFileSize(300);
            cloudlet.setOutputSize(300);

            cloudlet.setUtilizationModelCpu(
                    new UtilizationModelFull());

            cloudlet.setSubmissionDelay(job.arrival());

            cloudlets.add(cloudlet);

            jobByCloudletId.put(cloudletId, job);
        }

        /*
         * 7. Submit VMs and Cloudlets.
         */
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        /*
         * 8. Run the actual CloudSim simulation.
         */
        simulation.start();

        /*
         * 9. Retrieve completed Cloudlets.
         */
        List<Cloudlet> finished = new ArrayList<>(
                broker.getCloudletFinishedList());

        Set<Long> finishedIds = new HashSet<>();

        List<Map<String, Object>> resultJobs =
                new ArrayList<>();

        List<Map<String, Object>> events =
                new ArrayList<>();

        double waitingTotal = 0.0;
        double simulationEndTime = 0.0;

        int completed = 0;

        /*
         * Process completed Cloudlets.
         */
        for (Cloudlet cloudlet : finished) {

            Job job = jobByCloudletId.get(cloudlet.getId());

            if (job == null) {
                continue;
            }

            finishedIds.add(cloudlet.getId());

            String cloudletStatus =
                    cloudlet.getStatus().toString();

            boolean success =
                    cloudletStatus.equalsIgnoreCase("SUCCESS");

            String status = success
                    ? "COMPLETED"
                    : cloudletStatus;

            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getFinishTime();

            double waiting = Math.max(
                    0.0,
                    cloudlet.getWaitingTime());

            if (success) {
                completed++;
                waitingTotal += waiting;
            }

            simulationEndTime = Math.max(
                    simulationEndTime,
                    finish);

            Map<String, Object> row = job.asMap();

            row.put("status", status);
            row.put("start", round2(start));
            row.put("finish", round2(finish));

            row.put(
                    "vm",
                    cloudlet.getVm() == null
                            ? "—"
                            : "VM-" + (cloudlet.getVm().getId() + 1));

            row.put("waitingTime", round2(waiting));

            resultJobs.add(row);

            events.add(event(
                    job.arrival(),
                    job.id() + " arrived"));

            if (start >= 0) {
                events.add(event(
                        start,
                        job.id() + " started on " + row.get("vm")));
            }

            events.add(event(
                    finish,
                    job.id() + " finished with status " + status));
        }

        /*
         * Include Cloudlets that did not appear in the
         * finished list. Do not falsely count them as completed.
         */
        for (Cloudlet cloudlet : broker.getCloudletSubmittedList()) {

            if (finishedIds.contains(cloudlet.getId())) {
                continue;
            }

            Job job = jobByCloudletId.get(cloudlet.getId());

            if (job == null) {
                continue;
            }

            Map<String, Object> row = job.asMap();

            row.put("status", "NOT_COMPLETED");
            row.put("start", -1);
            row.put("finish", 0);
            row.put("vm", "—");
            row.put("waitingTime", 0);

            resultJobs.add(row);
        }

        /*
         * 10. Sort displayed results by process ID.
         */
        resultJobs.sort(
                Comparator.comparingInt(
                        row -> idNumber((String) row.get("id"))));

        events.sort(
                Comparator.comparingDouble(
                        event -> ((Number) event.get("time"))
                                .doubleValue()));

        /*
         * 11. Build the response in the format expected
         * by the existing JavaScript dashboard.
         */
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("algorithm", algorithmName(policy));

        result.put("totalJobs", source.size());
        result.put("completedJobs", completed);
        result.put("cancelledJobs", 0);
        result.put("notCompletedJobs", source.size() - completed);

        /*
         * This implementation does not perform preemption.
         */
        result.put("preemptions", 0);

        result.put(
                "averageWaitingTime",
                round2(waitingTotal / Math.max(1, completed)));

        result.put(
                "simulationEndTime",
                round2(simulationEndTime));

        result.put("jobs", resultJobs);
        result.put("events", events);

        return result;
    }

    private String algorithmName(Policy policy) {

        return switch (policy) {

            case FCFS ->
                    "FCFS (CloudSim Plus)";

            case SJF ->
                    "SJF (CloudSim Plus submission ordering)";

            case PAPER ->
                    "Paper-inspired deadline/priority ordering";
        };
    }

    private Map<String, Object> event(
            double time,
            String message) {

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("time", round2(time));
        result.put("message", message);

        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int idNumber(String id) {
        return Integer.parseInt(id.substring(1));
    }

    private enum Policy {
        FCFS,
        SJF,
        PAPER
    }

    private record Job(
            String id,
            int arrival,
            int execution,
            int deadline,
            int priority,
            String lease) {

        Map<String, Object> asMap() {

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put("id", id);
            result.put("arrival", arrival);
            result.put("execution", execution);
            result.put("deadline", deadline);
            result.put("priority", priority);
            result.put("lease", lease);

            return result;
        }
    }
}
