import { Terminal } from './xterm.mjs';
import { FitAddon } from './addon-fit.mjs';

const encoder = new TextEncoder();
const terminalElement = document.getElementById('terminal');
const fitAddon = new FitAddon();
const terminal = new Terminal({
  allowProposedApi: false,
  allowTransparency: false,
  convertEol: false,
  cursorBlink: true,
  cursorStyle: 'bar',
  drawBoldTextInBrightColors: true,
  fontFamily: 'ui-monospace, "SFMono-Regular", "Roboto Mono", monospace',
  fontSize: 12,
  lineHeight: 1.12,
  minimumContrastRatio: 4.5,
  rightClickSelectsWord: true,
  scrollback: 20000,
  smoothScrollDuration: 100,
  theme: {
    background: '#0b1014',
    foreground: '#dce7ee',
    cursor: '#70d6b1',
    cursorAccent: '#0b1014',
    selectionBackground: '#315b70aa',
    black: '#11181d',
    red: '#ff6b6b',
    green: '#70d6b1',
    yellow: '#ffd166',
    blue: '#62a9ff',
    magenta: '#c792ea',
    cyan: '#67d4e8',
    white: '#dce7ee',
    brightBlack: '#64737d',
    brightRed: '#ff8787',
    brightGreen: '#8ce5c5',
    brightYellow: '#ffe08a',
    brightBlue: '#8bc1ff',
    brightMagenta: '#d9a7f2',
    brightCyan: '#8ce6f2',
    brightWhite: '#ffffff',
  },
});

terminal.loadAddon(fitAddon);
// tmux uses the outer terminal's alternate buffer only to restore a local shell
// after detach. RemoteMux has no local shell behind this view, so retaining the
// normal buffer gives touch users independent scrollback without changing tmux.
terminal.parser.registerCsiHandler({ prefix: '?', final: 'h' }, (params) => (
  params.length === 1 && params[0] === 1049
));
terminal.parser.registerCsiHandler({ prefix: '?', final: 'l' }, (params) => (
  params.length === 1 && params[0] === 1049
));
terminal.open(terminalElement);

let nativePort = null;
let pointerMode = 'history';
let resizeTimer = null;
let wasAtBottom = true;
let unreadSinceLeave = 0;
let lastViewport = '';
let lastModes = '';
let lastReportedSize = '';
let fontSize = 12;
const pointers = new Map();
let lastPinchDistance = null;
let lastTwoFingerCenterY = null;
let twoFingerStartDistance = null;
let twoFingerStartCenterY = null;
let twoFingerGesture = null;
const twoFingerMovedPointers = new Set();
let accumulatedScrollPixels = 0;
let historyVelocity = 0;
let historyGestureActive = false;
let historyGestureMoved = false;
let suppressNextClick = false;
let inertiaFrame = null;

function encodeBase64Url(bytes) {
  let binary = '';
  const chunk = 0x4000;
  for (let offset = 0; offset < bytes.length; offset += chunk) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
  }
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

function decodeBase64Url(value) {
  const standard = value.replaceAll('-', '+').replaceAll('_', '/');
  const padded = standard + '='.repeat((4 - (standard.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function post(message) {
  if (!nativePort) return false;
  nativePort.postMessage(JSON.stringify(message));
  return true;
}

function sendInput(bytes) {
  if (bytes.length > 0) {
    post({ type: 'input', data: encodeBase64Url(bytes) });
  }
}

function reportViewport() {
  const buffer = terminal.buffer.active;
  const atBottom = buffer.viewportY >= buffer.baseY;
  if (wasAtBottom && !atBottom) {
    unreadSinceLeave = 0;
  }
  if (atBottom) {
    unreadSinceLeave = 0;
  }
  wasAtBottom = atBottom;
  const viewport = {
    type: 'viewport',
    atBottom,
    distance: Math.max(0, buffer.baseY - buffer.viewportY),
    unread: atBottom ? 0 : unreadSinceLeave,
  };
  const serialized = JSON.stringify(viewport);
  if (serialized !== lastViewport && post(viewport)) {
    lastViewport = serialized;
  }
}

function reportModes() {
  const modes = {
    type: 'modes',
    alternateScreen: terminal.buffer.active.type === 'alternate',
    bracketedPaste: terminal.modes.bracketedPasteMode,
    mouseTracking: terminal.modes.mouseTrackingMode,
  };
  const serialized = JSON.stringify(modes);
  if (serialized !== lastModes && post(modes)) {
    lastModes = serialized;
  }
}

function reportResize() {
  const size = `${terminal.cols}x${terminal.rows}`;
  if (size !== lastReportedSize && post({ type: 'resize', cols: terminal.cols, rows: terminal.rows })) {
    lastReportedSize = size;
  }
}

function fitAndReport() {
  fitAddon.fit();
  reportResize();
}

function scheduleFit() {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(fitAndReport, 200);
}

function scrollHistoryByPixels(deltaPixels) {
  accumulatedScrollPixels += deltaPixels;
  const cellHeight = Math.max(8, terminalElement.clientHeight / Math.max(1, terminal.rows));
  const lines = Math.trunc(accumulatedScrollPixels / cellHeight);
  if (lines !== 0) {
    const before = terminal.buffer.active.viewportY;
    terminal.scrollLines(lines);
    accumulatedScrollPixels -= lines * cellHeight;
    if (terminal.buffer.active.viewportY === before) {
      accumulatedScrollPixels = 0;
      return false;
    }
    reportViewport();
  }
  return true;
}

function cancelInertia() {
  if (inertiaFrame !== null) {
    cancelAnimationFrame(inertiaFrame);
    inertiaFrame = null;
  }
}

function startInertia(velocity) {
  cancelInertia();
  velocity = Math.max(-2.5, Math.min(2.5, velocity));
  if (Math.abs(velocity) < 0.04) return;
  let lastFrame = performance.now();
  const tick = (now) => {
    const elapsed = Math.min(32, Math.max(1, now - lastFrame));
    lastFrame = now;
    const canContinue = scrollHistoryByPixels(velocity * elapsed);
    velocity *= Math.pow(0.94, elapsed / 16);
    if (canContinue && Math.abs(velocity) >= 0.025) {
      inertiaFrame = requestAnimationFrame(tick);
    } else {
      accumulatedScrollPixels = 0;
      inertiaFrame = null;
      reportViewport();
    }
  };
  inertiaFrame = requestAnimationFrame(tick);
}

terminal.onData((data) => sendInput(encoder.encode(data)));
terminal.onBinary((data) => {
  const bytes = Uint8Array.from(data, (character) => character.charCodeAt(0) & 0xff);
  sendInput(bytes);
});
terminal.onResize(reportResize);
terminal.onScroll(reportViewport);
terminal.onWriteParsed(() => {
  reportModes();
  reportViewport();
});
terminal.onTitleChange((title) => post({ type: 'title', title }));

terminal.attachCustomWheelEventHandler((event) => {
  if (pointerMode === 'application') {
    return true;
  }
  event.preventDefault();
  cancelInertia();
  scrollHistoryByPixels(event.deltaY);
  return false;
});

terminalElement.addEventListener('pointerdown', (event) => {
  cancelInertia();
  pointers.set(event.pointerId, {
    x: event.clientX,
    y: event.clientY,
    time: event.timeStamp,
  });
  if (pointerMode === 'history' || pointers.size > 1) {
    terminalElement.setPointerCapture(event.pointerId);
    historyGestureActive = true;
  }
  if (pointers.size === 1) {
    historyVelocity = 0;
    historyGestureMoved = false;
  } else if (pointers.size === 2) {
    const values = [...pointers.values()];
    historyVelocity = 0;
    lastPinchDistance = Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y);
    lastTwoFingerCenterY = (values[0].y + values[1].y) / 2;
    twoFingerStartDistance = lastPinchDistance;
    twoFingerStartCenterY = lastTwoFingerCenterY;
    twoFingerGesture = pointerMode === 'application' ? 'scroll' : null;
    twoFingerMovedPointers.clear();
  }
});

terminalElement.addEventListener('pointermove', (event) => {
  const previous = pointers.get(event.pointerId);
  if (!previous) return;
  const current = { x: event.clientX, y: event.clientY, time: event.timeStamp };
  pointers.set(event.pointerId, current);

  const localHistoryGesture = pointerMode === 'history' || pointers.size > 1;
  if (!localHistoryGesture) return;
  event.preventDefault();

  if (pointers.size === 2) {
    const values = [...pointers.values()];
    const distance = Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y);
    const centerY = (values[0].y + values[1].y) / 2;
    twoFingerMovedPointers.add(event.pointerId);
    if (twoFingerGesture === null) {
      const distanceDelta = distance - twoFingerStartDistance;
      const centerDelta = centerY - twoFingerStartCenterY;
      const enoughMovement = twoFingerMovedPointers.size === 2
        ? Math.max(Math.abs(distanceDelta), Math.abs(centerDelta)) >= 8
        : Math.max(Math.abs(distanceDelta), Math.abs(centerDelta)) >= 20;
      if (enoughMovement) {
        twoFingerGesture = Math.abs(distanceDelta) > Math.abs(centerDelta) * 1.4
          ? 'pinch'
          : 'scroll';
      }
    }
    if (twoFingerGesture === 'scroll') {
      if (lastTwoFingerCenterY !== null) {
        const delta = lastTwoFingerCenterY - centerY;
        const elapsed = Math.max(1, event.timeStamp - previous.time);
        const sampleVelocity = Math.max(-2.5, Math.min(2.5, delta / elapsed));
        historyVelocity = historyVelocity * 0.65 + sampleVelocity * 0.35;
        historyGestureMoved ||= Math.abs(delta) >= 1;
        scrollHistoryByPixels(delta);
      }
      lastTwoFingerCenterY = centerY;
    } else if (twoFingerGesture === 'pinch') {
      if (lastPinchDistance !== null && Math.abs(distance - lastPinchDistance) >= 6) {
        const nextSize = Math.max(8, Math.min(28, fontSize + (distance > lastPinchDistance ? 1 : -1)));
        if (nextSize !== fontSize) {
          fontSize = nextSize;
          terminal.options.fontSize = fontSize;
          scheduleFit();
          post({ type: 'font_size', value: fontSize });
        }
        historyGestureMoved = true;
      }
      lastPinchDistance = distance;
    }
  } else {
    const delta = previous.y - event.clientY;
    const elapsed = Math.max(1, event.timeStamp - previous.time);
    const sampleVelocity = Math.max(-2.5, Math.min(2.5, delta / elapsed));
    historyVelocity = historyVelocity * 0.65 + sampleVelocity * 0.35;
    historyGestureMoved ||= Math.abs(delta) >= 1;
    scrollHistoryByPixels(delta);
  }
});

function finishPointer(event) {
  pointers.delete(event.pointerId);
  if (pointers.size < 2) {
    lastPinchDistance = null;
    lastTwoFingerCenterY = null;
    twoFingerStartDistance = null;
    twoFingerStartCenterY = null;
    twoFingerGesture = null;
    twoFingerMovedPointers.clear();
  }
  if (pointers.size === 0 && historyGestureActive) {
    if (historyGestureMoved) {
      suppressNextClick = true;
      setTimeout(() => { suppressNextClick = false; }, 300);
      startInertia(historyVelocity);
    }
    historyGestureActive = false;
  }
}

terminalElement.addEventListener('pointerup', finishPointer);
terminalElement.addEventListener('pointercancel', finishPointer);
terminalElement.addEventListener('click', (event) => {
  if (suppressNextClick) {
    suppressNextClick = false;
    event.preventDefault();
    return;
  }
  post({ type: 'focus_request' });
});

window.addEventListener('message', (event) => {
  if (event.data !== 'remux-init' || !event.ports || event.ports.length !== 1 || nativePort) {
    return;
  }
  nativePort = event.ports[0];
  nativePort.onmessage = (portEvent) => {
    let message;
    try {
      message = JSON.parse(portEvent.data);
    } catch (_error) {
      post({ type: 'error', message: 'invalid native message' });
      return;
    }
    switch (message.type) {
      case 'output': {
        const bytes = decodeBase64Url(message.data);
        if (!wasAtBottom) {
          const lineFeeds = bytes.reduce((count, byte) => count + (byte === 0x0a ? 1 : 0), 0);
          unreadSinceLeave = Math.min(9999, unreadSinceLeave + Math.max(1, lineFeeds));
        }
        terminal.write(bytes, () => {
          post({ type: 'output_ack', id: message.id });
          reportModes();
          reportViewport();
        });
        break;
      }
      case 'fit':
        scheduleFit();
        break;
      case 'focus':
        terminal.focus();
        break;
      case 'scroll_to_bottom':
        cancelInertia();
        accumulatedScrollPixels = 0;
        terminal.scrollToBottom();
        reportViewport();
        break;
      case 'scroll_lines':
        cancelInertia();
        accumulatedScrollPixels = 0;
        terminal.scrollLines(message.lines);
        reportViewport();
        break;
      case 'scroll_pages':
        cancelInertia();
        accumulatedScrollPixels = 0;
        terminal.scrollPages(message.pages);
        reportViewport();
        break;
      case 'set_font_size':
        fontSize = Math.max(8, Math.min(28, message.value));
        terminal.options.fontSize = fontSize;
        scheduleFit();
        break;
      case 'set_pointer_mode':
        pointerMode = message.value === 'application' ? 'application' : 'history';
        post({ type: 'pointer_mode', value: pointerMode });
        break;
      case 'set_screen_reader':
        terminal.options.screenReaderMode = Boolean(message.enabled);
        break;
      default:
        post({ type: 'error', message: `unsupported native message: ${message.type}` });
    }
  };
  nativePort.start();
  fitAndReport();
  reportModes();
  reportViewport();
  post({ type: 'ready', cols: terminal.cols, rows: terminal.rows });
});

new ResizeObserver(scheduleFit).observe(terminalElement);
window.addEventListener('focus', () => terminal.focus());
