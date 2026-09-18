// Only canonical transcript Tool calls/results count as execution evidence.
export const FIXTURE = 'io.github.boxuechen.clawinone.fixture';
export const COUNTER = `${FIXTURE}:id/fixture_smoke_increment`;
export const messageText = message => typeof message.content === 'string' ? message.content :
  (message.content ?? []).filter(c => c.type === 'text').map(c => c.text ?? '').join('\n');

export function corePrompt(id) {
  return `CLAW-SMOKE:${id}. In this single run, use android_app to inspect .claw-verification/${id}/fixture-v1.apk and place_vscreen_workload with that receipt. Then use only android_use on ${FIXTURE}: observe VScreen; from that exact response copy controlId, snapshotId, and the Smoke count: 0 semantic ref; make exactly one activate call containing all three copied values; observe Smoke count: 1 on the same VScreen display; make one stop call with the same controlId; and finish. Before the activate call, verify that snapshotId is present. Use operation activate, never tap or coordinates. If any call fails, any required argument is missing, or the target is not VScreen, stop safely and report failure without retrying. Do not install, build, use shell, change settings, use another tool, omit snapshotId, or repeat an operation.`;
}

function payload(message) {
  try { return JSON.parse(messageText(message)); } catch { return null; }
}

export function evaluateCore(messages, request, session) {
  const pending = reason => ({status: 'NOT RUN', reason});
  const fail = reason => ({status: 'FAIL', reason});
  const starts = messages.map((m, i) => m.role === 'user' && messageText(m) === corePrompt(request.id) ? i : -1).filter(i => i >= 0);
  if (starts.length !== 1) return starts.length ? fail('Duplicate smoke send') : pending('Waiting for the exact native Chat prompt');
  const following = messages.slice(starts[0] + 1);
  if (following.some(message => message.role === 'user')) return fail('Unexpected or repeated user message in smoke Chat');
  const runIds = [...new Set(following.filter(m => ['assistant', 'toolResult'].includes(m.role)).map(m => m.__openclaw?.runId).filter(Boolean))];
  if (runIds.length > 1) return fail('Unexpected Gateway run; no automatic replay accepted');
  if (!runIds.length) return pending('Waiting for the Gateway run');
  const runId = runIds[0];
  if (session.permissionMode !== 'full' || session.permissionModePending === true) return fail('Core smoke requires explicitly applied Full access');
  const calls = new Map();
  const results = [];
  for (const message of following) {
    if (!['assistant', 'toolResult'].includes(message.role)) continue;
    if (message.__openclaw?.runId !== runId) return fail('Missing or mismatched canonical run attribution');
    if (message.role === 'assistant') {
      for (const content of Array.isArray(message.content) ? message.content : []) {
        if (content.type !== 'toolCall') continue;
        if (calls.has(content.id)) return fail('Duplicate Tool call identity');
        if (!['android_app', 'android_use'].includes(content.name)) return fail('Unexpected Tool in bounded smoke');
        const allowed = content.name === 'android_use' ? ['observe', 'activate', 'stop'] : ['inspect_apk', 'place_vscreen_workload'];
        if (!allowed.includes(content.arguments?.operation) ||
            (content.name === 'android_use' && content.arguments.targetPackage !== FIXTURE)) {
          return fail('Unexpected operation or target in bounded smoke');
        }
        calls.set(content.id, {...content, runId});
      }
    } else {
      const call = calls.get(message.toolCallId);
      if (!call || call.runId !== runId || call.name !== message.toolName || results.some(result => result.call.id === call.id)) {
        return fail('Unmatched or duplicate Tool result');
      }
      if (message.isError === true) return fail('Tool failed; do not retry automatically');
      const data = payload(message);
      if (!data) return fail('Missing structured Tool result');
      results.push({call, data});
    }
  }
  const ended = following.some(m => m.role === 'assistant' && ['stop', 'end_turn'].includes(m.stopReason));
  const complete = session.hasActiveRun === false && session.lastRunId === runId && ended;
  const incomplete = reason => complete ? {...fail(reason), runId} : {...pending(reason), runId};
  const inspect = results.findIndex(result => result.call.name === 'android_app' && result.call.arguments?.operation === 'inspect_apk' &&
    result.data.status === 'inspected' && result.data.artifact?.packageName === FIXTURE && result.data.artifact.sha256 === request.fixtureSha256);
  if (inspect < 0) return incomplete('Missing candidate APK inspection');
  const artifactId = results[inspect].data.artifact.artifactId;
  const launch = results.findIndex((result, index) => index > inspect && result.call.name === 'android_app' &&
    result.call.arguments?.operation === 'place_vscreen_workload' && result.call.arguments.artifactId === artifactId &&
    result.data.status === 'vscreen_workload_requested' && result.data.packageName === FIXTURE && result.data.artifact?.artifactId === artifactId);
  if (launch < 0) return incomplete('Missing receipt-bound launch');
  if ([...calls.values()].filter(call => call.name === 'android_app').length !== 2) {
    return fail('Preparation must inspect and launch exactly once');
  }
  const snapshot = (result, count) => result.call.name === 'android_use' && result.call.arguments?.operation === 'observe' &&
    result.call.arguments.targetPackage === FIXTURE && result.data.package === FIXTURE && result.data.targetPackage === FIXTURE &&
    result.data.display?.kind === 'vscreen' && Number.isInteger(result.data.display.id) && result.data.display.id > 0 &&
    typeof result.data.controlId === 'string' && typeof result.data.snapshotId === 'string' &&
    result.data.nodes?.some(node => node.viewId === COUNTER && node.text?.toLowerCase() === `smoke count: ${count}`);
  const before = results.findIndex((result, index) => index > launch && snapshot(result, 0));
  if (before < 0) return incomplete('Missing initial VScreen observation');
  const initial = results[before].data;
  const ref = initial.nodes.find(node => node.viewId === COUNTER).ref;
  const actions = results.filter(result => result.call.name === 'android_use' && !['observe', 'stop'].includes(result.call.arguments?.operation));
  if (actions.length > 1) return fail('More than one Android action');
  const action = results.findIndex((result, index) => index > before && result.call.name === 'android_use' &&
    result.call.arguments?.operation === 'activate' && result.call.arguments.targetPackage === FIXTURE &&
    result.call.arguments.controlId === initial.controlId && result.call.arguments.snapshotId === initial.snapshotId &&
    result.call.arguments.ref === ref && ['completed', 'accepted_but_unverified'].includes(result.data.code) &&
    result.data.controlId === initial.controlId && result.data.targetPackage === FIXTURE);
  if (action < 0) return incomplete('Missing one successful semantic activation');
  const after = results.findIndex((result, index) => index > action && snapshot(result, 1) &&
    result.data.controlId === initial.controlId && result.data.display.id === initial.display.id &&
    result.data.snapshotId !== initial.snapshotId);
  if (after < 0) return incomplete('Missing changed counter on the same VScreen/control');
  const stop = results.findIndex((result, index) => index > after && result.call.name === 'android_use' &&
    result.call.arguments?.operation === 'stop' && result.call.arguments.targetPackage === FIXTURE &&
    result.call.arguments.controlId === initial.controlId && result.data.status === 'stopped');
  if (stop < 0) return incomplete('Missing confirmed native control stop');
  if (!complete) return incomplete('Waiting for the captured run to finish');
  if (calls.size !== results.length) return fail('Unresolved Tool call remains');
  if (calls.size !== 6) return fail('Smoke must use exactly six Tool calls');
  return {status: 'PASS', runId, runIds, displayId: initial.display.id, toolCalls: calls.size,
    checks: ['candidate-inspected', 'receipt-launched', 'vscreen-observed', 'semantic-activated-once', 'counter-verified', 'control-stopped', 'run-finished']};
}
