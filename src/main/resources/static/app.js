const $ = (id) => document.getElementById(id);
let latest = null;

async function apiHealthy() {
  try {
    const r = await fetch('/api/health');
    const data = await r.json();
    $('apiStatus').textContent = data.status === 'UP' ? 'Online' : 'Error';
  } catch (_) {
    $('apiStatus').textContent = 'Offline';
  }
}

function renderSummary(target, data) {
  const el = $(target);
  if (!data) { el.textContent = 'Not run in this request.'; el.className = 'summary empty'; return; }
  el.className = 'summary';
  const metrics = [
    ['Completed jobs', `${data.completedJobs}/${data.totalJobs}`],
    ['Cancelled jobs', data.cancelledJobs],
    ['Preemptions', data.preemptions],
    ['Average waiting time', data.averageWaitingTime],
    ['Simulation end time', data.simulationEndTime],
    ['Not completed', data.notCompletedJobs]
  ];
  el.innerHTML = metrics.map(([name,value]) => `<div class="stat"><span>${name}</span><strong>${value}</strong></div>`).join('');
}

function renderWorkload(jobs) {
  $('jobCount').textContent = jobs.length;
  $('workloadBody').innerHTML = jobs.map(j => `<tr><td>${j.id}</td><td>${j.arrival}</td><td>${j.execution}</td><td>${j.deadline}</td><td>${j.priority}</td><td>${j.lease}</td></tr>`).join('');
}

function renderDetails(which) {
  const data = latest && latest[which];
  if (!data) {
    $('resultsBody').innerHTML = '<tr><td colspan="6">Run this algorithm to see job results.</td></tr>';
    return;
  }
  $('resultsBody').innerHTML = data.jobs.map(j => `<tr><td>${j.id}</td><td><span class="pill ${j.status}">${j.status.replaceAll('_',' ')}</span></td><td>${j.lease}</td><td>${j.start < 0 ? '—' : j.start}</td><td>${j.finish || '—'}</td><td>${j.vm || '—'}</td></tr>`).join('');
}

async function run(mode) {
  $('status').textContent = 'Running simulation…';
  ['runBoth','runPaper','runModified'].forEach(id => $(id).disabled = true);
  try {
    const seed = Math.max(1, Number.parseInt($('seed').value || '1', 10));
    const response = await fetch(`/api/simulation/run?mode=${mode}&seed=${seed}`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || 'Request failed');
    latest = data;
    renderWorkload(data.workload || []);
    renderSummary('paperSummary', data.paper);
    renderSummary('modifiedSummary', data.modified);
    const current = $('detailMode').value;
    if (!data[current]) $('detailMode').value = mode === 'MODIFIED' ? 'modified' : 'paper';
    renderDetails($('detailMode').value);
    $('status').textContent = `Success · mode ${mode} · seed ${seed}`;
  } catch (err) {
    $('status').textContent = `Error: ${err.message}`;
  } finally {
    ['runBoth','runPaper','runModified'].forEach(id => $(id).disabled = false);
  }
}
$('runBoth').addEventListener('click', () => run('BOTH'));
$('runPaper').addEventListener('click', () => run('PAPER'));
$('runModified').addEventListener('click', () => run('MODIFIED'));
$('detailMode').addEventListener('change', () => renderDetails($('detailMode').value));
apiHealthy();
run('BOTH');
