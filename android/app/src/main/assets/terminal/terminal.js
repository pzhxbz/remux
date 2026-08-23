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
let lastModes = '';
let fontSize = 12;
const pointers = new Map();
let lastPinchDistance = null;
let accumulatedScrollPixels = 0;

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
  nativePort?.postMessage(JSON.stringify(message));
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
  post({
    type: 'viewport',
    atBottom,
    distance: Math.max(0, buffer.baseY - buffer.viewportY),
    unread: atBottom ? 0 : unreadSinceLeave,
  });
}

function reportModes() {
  const modes = {
    type: 'modes',
    alternateScreen: terminal.buffer.active.type === 'alternate',
    bracketedPaste: terminal.modes.bracketedPasteMode,
    mouseTracking: terminal.modes.mouseTrackingMode,
  };
  const serialized = JSON.stringify(modes);
  if (serialized !== lastModes) {
    lastModes = serialized;
    post(modes);
  }
}

function fitAndReport() {
  fitAddon.fit();
  post({ type: 'resize', cols: terminal.cols, rows: terminal.rows });
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
    terminal.scrollLines(lines);
    accumulatedScrollPixels -= lines * cellHeight;
    reportViewport();
  }
}

terminal.onData((data) => sendInput(encoder.encode(data)));
terminal.onBinary((data) => {
  const bytes = Uint8Array.from(data, (character) => character.charCodeAt(0) & 0xff);
  sendInput(bytes);
});
terminal.onResize(({ cols, rows }) => post({ type: 'resize', cols, rows }));
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
  scrollHistoryByPixels(event.deltaY);
  return false;
});

terminalElement.addEventListener('pointerdown', (event) => {
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
  if (pointerMode === 'history' || pointers.size > 1) {
    terminalElement.setPointerCapture(event.pointerId);
  }
});

terminalElement.addEventListener('pointermove', (event) => {
  const previous = pointers.get(event.pointerId);
  if (!previous) return;
  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

  const localHistoryGesture = pointerMode === 'history' || pointers.size > 1;
  if (!localHistoryGesture) return;
  event.preventDefault();

  if (pointers.size === 2) {
    const values = [...pointers.values()];
    const distance = Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y);
    if (lastPinchDistance !== null && Math.abs(distance - lastPinchDistance) >= 6) {
      const nextSize = Math.max(8, Math.min(28, fontSize + (distance > lastPinchDistance ? 1 : -1)));
      if (nextSize !== fontSize) {
        fontSize = nextSize;
        terminal.options.fontSize = fontSize;
        scheduleFit();
        post({ type: 'font_size', value: fontSize });
      }
    }
    lastPinchDistance = distance;
  } else {
    scrollHistoryByPixels(previous.y - event.clientY);
  }
});

function finishPointer(event) {
  pointers.delete(event.pointerId);
  if (pointers.size < 2) lastPinchDistance = null;
}

terminalElement.addEventListener('pointerup', finishPointer);
terminalElement.addEventListener('pointercancel', finishPointer);
terminalElement.addEventListener('click', () => post({ type: 'focus_request' }));

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
        terminal.scrollToBottom();
        reportViewport();
        break;
      case 'scroll_lines':
        terminal.scrollLines(message.lines);
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
