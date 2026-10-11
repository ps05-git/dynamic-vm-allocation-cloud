# Dynamic VM Allocation — Cloud-Hosted Demo

This is a starter project for a cloud-computing research demonstration. It contains:
- Spring Boot REST API
- Browser dashboard served by the same application
- Dockerfile for container deployment
- A reproducible, custom discrete-event scheduling demonstration comparing a paper-inspired policy with a modified policy

## Important scope note
The scheduling engine is a self-contained educational simulation inspired by the supplied paper's concepts (deadline/priority and lease-aware preemption). It is **not** the CloudSim Plus framework and does not claim to reproduce the paper's exact numerical results. The dashboard labels the policy and metrics transparently.

## Requirements for local run
- JDK 21
- Maven 3.9+
- Internet access for the first Maven dependency download

## Run on Windows PowerShell
```powershell
java -version
mvn -version
mvn spring-boot:run
```
Open `http://localhost:8080`.

If Maven is not installed but Docker Desktop is:
```powershell
docker build -t dynamic-vm-allocation .
docker run --rm -p 8080:8080 dynamic-vm-allocation
```

## Deploy on Render
1. Create a GitHub repository and upload all files in this project.
2. Sign in to Render and choose **New + → Web Service**.
3. Connect the GitHub repository.
4. Choose Docker as the runtime if asked. The included `Dockerfile` builds the app.
5. Keep the health check path as `/api/health` (the included `render.yaml` also declares it).
6. Deploy, wait for the build to finish, and open the generated `https://....onrender.com` URL.
7. Test `/api/health` and then the dashboard root `/`.

Free instances may sleep when idle and the first request after sleeping can be slow.

## API
- `GET /api/health`
- `GET /api/simulation/run?mode=BOTH&seed=1`
- `GET /api/simulation/run?mode=PAPER&seed=1`
- `GET /api/simulation/run?mode=SJF&seed=1`

The same seed produces the same workload for repeatable comparisons.

## Presentation flow
1. Show the public dashboard.
2. Explain GitHub → Render Docker build → Spring Boot API → scheduling engine → dashboard.
3. Click **Run both algorithms**.
4. Explain job count, completed/cancelled jobs, preemptions, waiting time, and end time.
5. State the scope honestly: this is a custom simulation prototype inspired by the paper; CloudSim Plus integration can be a next stage if specifically required.
