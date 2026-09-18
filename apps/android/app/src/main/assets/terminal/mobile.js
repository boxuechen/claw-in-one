/* Pinned upstream terminal presentation adapter. No native interface, RPC, or command queue. */
(() => {
  if (window.clawTerminal) return window.clawTerminal.snapshot();
  const panel = () => document.querySelector('openclaw-terminal-panel');
  const tab = () => { const p = panel(); return p?.terminalSessions?.tabs.find(t => t.id === p.terminalSessions.activeId); };
  let previousId = window.__clawPreviousSession || null, replacement = null, acknowledgedId = null, selectedLine = null;
  const adapted = new WeakSet();
  const install = () => {
    const p = panel(), root = p?.renderRoot;
    if (!root) return;
    if (!root.querySelector('#claw-terminal-style')) {
      const style = document.createElement('style'); style.id = 'claw-terminal-style';
      style.textContent = '.tp-header{display:none!important}.tp-viewport,.tp-host{touch-action:none}.tp-host{padding:6px!important}';
      root.append(style);
    }
    const t=tab()?.controller.terminal;
    if(t?.selectionManager && !adapted.has(t)) {
      // Pinned Ghostty uses bottom-relative scrolling but its selection manager expects
      // a top-relative buffer row. Keep selection/copy aligned with the visible canvas.
      t.selectionManager.getViewportY=()=>Math.max(0,t.getScrollbackLength()-t.getViewportY());
      adapted.add(t);
    }
  };
  const phase = () => {
    const p = panel(), a = tab();
    const gateway = document.querySelector('openclaw-app')?.runtime?.context?.gateway?.snapshot;
    if (gateway?.phase !== 'connected') {
      if (gateway?.lastErrorCode?.startsWith('AUTH_') || gateway?.lastErrorCode === 'PAIRING_REQUIRED') return 'authorizationRequired';
      return 'connecting';
    }
    if (!p?.available) return 'unavailable';
    if (!a) return 'preparing';
    if (a.gatewaySessionId && a.gatewaySessionId !== previousId) {
      if (previousId) replacement = a.gatewaySessionId;
      previousId = a.gatewaySessionId;
    }
    if (a.status === 'exited') return 'exited';
    if (replacement && acknowledgedId !== replacement) return 'replaced';
    return a.status === 'live' ? 'live' : 'preparing';
  };
  const input = (text, paste) => {
    if (phase() !== 'live') return false;
    const t = tab().controller.terminal;
    if (paste) t.paste(text); else t.input(text, true);
    return true;
  };
  window.clawTerminal = {
    snapshot() { install(); return {phase:phase(), sessionId:tab()?.gatewaySessionId || null, selected:!!tab()?.controller.terminal.hasSelection()}; },
    input(text) { return input(text, false); },
    paste(text) { return input(text, true); },
    copy() { return tab()?.controller.terminal.getSelection() || ''; },
    clearSelection() { selectedLine=null; tab()?.controller.terminal.clearSelection(); },
    scroll(fraction) { const t=tab()?.controller.terminal; if(t)t.scrollLines(Math.round(fraction*t.rows)); },
    select(fraction, extend) {
      const t=tab()?.controller.terminal; if(!t)return;
      const line=Math.min(t.rows-1,Math.max(0,Math.floor(fraction*t.rows)));
      if(!extend || selectedLine===null)selectedLine=line;
      t.selectLines(Math.min(selectedLine,line),Math.max(selectedLine,line));
    },
    fit() { tab()?.controller.fit(); },
    acknowledge() { acknowledgedId=replacement; return true; },
    async newSession() {
      if(phase()!=='exited')return false;
      const p=panel(); p.terminalSessions.closeTab(p.terminalSessions.activeId);
      await p.terminalSessions.openSession();
      previousId=tab()?.gatewaySessionId || null; replacement=null; acknowledgedId=null; return true;
    }
  };
  return window.clawTerminal.snapshot();
})();
