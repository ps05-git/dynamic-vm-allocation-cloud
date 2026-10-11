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
