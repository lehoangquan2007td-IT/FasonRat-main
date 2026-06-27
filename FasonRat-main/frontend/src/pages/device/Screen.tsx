import { useState, useCallback, useRef, useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext } from '@/types';
import { CMD } from '@/types';
import { DevicePageHeader, ErrorAlert, StatusBadge } from '@/components/device/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  onScreenFrame,
  onScreenStopped,
  onScreenStatus,
  onScreenError,
} from '@/services/socket';
import {
  Monitor,
  Play,
  Square,
  ArrowLeft,
  Home,
  LayoutGrid,
  Send,
  AlertTriangle,
  Radio,
  Plug,
  Loader2,
  Unplug,
  Wifi,
} from 'lucide-react';

function mapPointerToDevice(
  clientX: number,
  clientY: number,
  rect: DOMRect,
  screenW: number,
  screenH: number,
): { x: number; y: number } | null {
  if (!screenW || !screenH) return null;

  const containerAspect = rect.width / rect.height;
  const screenAspect = screenW / screenH;

  let renderW: number;
  let renderH: number;
  let offsetX: number;
  let offsetY: number;

  if (containerAspect > screenAspect) {
    renderH = rect.height;
    renderW = renderH * screenAspect;
    offsetX = (rect.width - renderW) / 2;
    offsetY = 0;
  } else {
    renderW = rect.width;
    renderH = renderW / screenAspect;
    offsetX = 0;
    offsetY = (rect.height - renderH) / 2;
  }

  const x = clientX - rect.left - offsetX;
  const y = clientY - rect.top - offsetY;

  if (x < 0 || y < 0 || x > renderW || y > renderH) return null;

  return {
    x: Math.round((x / renderW) * screenW),
    y: Math.round((y / renderH) * screenH),
  };
}

type ConnectionState = 'disconnected' | 'connecting' | 'connected';

export default function ScreenPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();

  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected');
  const [streaming, setStreaming] = useState(false);
  const [frameSrc, setFrameSrc] = useState<string | null>(null);
  const [screenWidth, setScreenWidth] = useState(0);
  const [screenHeight, setScreenHeight] = useState(0);
  const [fps, setFps] = useState(0);
  const [quality, setQuality] = useState(40);
  const [targetFps, setTargetFps] = useState(3);
  const [accessible, setAccessible] = useState<boolean | null>(null);
  const [screenError, setScreenError] = useState<string | null>(null);
  const [textInput, setTextInput] = useState('');

  const viewportRef = useRef<HTMLDivElement>(null);
  const pointerStart = useRef<{ x: number; y: number; time: number } | null>(null);
  const frameCountRef = useRef(0);
  const fpsTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const connectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const { sendCommand, commandStatus } = useDeviceData<Record<string, never>>({
    clientId,
    page: 'screen',
    extractData: () => ({}),
    dataType: 'screen',
    defaultValue: {},
    socketDebounceMs: 5000,
  });

  // FPS counter
  useEffect(() => {
    fpsTimerRef.current = setInterval(() => {
      setFps(frameCountRef.current);
      frameCountRef.current = 0;
    }, 1000);
    return () => {
      if (fpsTimerRef.current) clearInterval(fpsTimerRef.current);
    };
  }, []);

  // Socket listeners
  useEffect(() => {
    const unsubFrame = onScreenFrame((payload) => {
      if (payload.id !== clientId) return;
      frameCountRef.current += 1;
      setFrameSrc(`data:image/jpeg;base64,${payload.frame}`);
      if (payload.screenWidth) setScreenWidth(payload.screenWidth);
      if (payload.screenHeight) setScreenHeight(payload.screenHeight);
      setStreaming(true);
      setConnectionState('connected');
      setScreenError(null);
      // Clear connection timeout since we got frames
      if (connectTimeoutRef.current) {
        clearTimeout(connectTimeoutRef.current);
        connectTimeoutRef.current = null;
      }
    });

    const unsubStopped = onScreenStopped((payload) => {
      if (payload.id !== clientId) return;
      setStreaming(false);
      setFrameSrc(null);
      setConnectionState('disconnected');
    });

    const unsubStatus = onScreenStatus((payload) => {
      if (payload.id !== clientId) return;
      if (payload.streaming !== undefined) {
        setStreaming(payload.streaming);
        if (payload.streaming) {
          setConnectionState('connected');
        }
      }
      if (payload.screenWidth) setScreenWidth(payload.screenWidth);
      if (payload.screenHeight) setScreenHeight(payload.screenHeight);
      if (payload.fps !== undefined) setTargetFps(payload.fps);
      if (payload.quality !== undefined) setQuality(payload.quality);
      if (payload.accessible !== undefined) setAccessible(payload.accessible);
    });

    const unsubError = onScreenError((payload) => {
      if (payload.id !== clientId) return;
      setScreenError(payload.error);
      setStreaming(false);
      setConnectionState('disconnected');
    });

    return () => {
      unsubFrame();
      unsubStopped();
      unsubStatus();
      unsubError();
      if (connectTimeoutRef.current) {
        clearTimeout(connectTimeoutRef.current);
      }
    };
  }, [clientId]);

  const requestStatus = useCallback(async () => {
    try {
      await sendCommand(CMD.SCREEN, { action: 'status' });
      await sendCommand(CMD.SCREEN_CTRL, { action: 'status' });
    } catch {
      // ignore
    }
  }, [sendCommand]);

  useEffect(() => {
    if (online) requestStatus();
  }, [online, requestStatus]);

  // Connect: start stream + request accessibility status
  const handleConnect = useCallback(async () => {
    setScreenError(null);
    setConnectionState('connecting');
    try {
      // Start screen streaming
      await sendCommand(CMD.SCREEN, { action: 'start', fps: targetFps, quality });
      // Also check accessibility for remote control
      await sendCommand(CMD.SCREEN_CTRL, { action: 'status' });
      // Set timeout - if no frame received in 15s, consider connection failed
      connectTimeoutRef.current = setTimeout(() => {
        if (!streaming) {
          setConnectionState('disconnected');
          setScreenError('Connection timed out. The device may need to approve screen capture permission.');
        }
      }, 15000);
    } catch {
      setConnectionState('disconnected');
    }
  }, [sendCommand, targetFps, quality, streaming]);

  // Disconnect: stop stream
  const handleDisconnect = useCallback(async () => {
    try {
      await sendCommand(CMD.SCREEN, { action: 'stop' });
      setStreaming(false);
      setFrameSrc(null);
      setConnectionState('disconnected');
    } catch {
      // ignore
    }
  }, [sendCommand]);

  const sendTap = useCallback(async (x: number, y: number) => {
    try {
      await sendCommand(CMD.SCREEN_CTRL, { action: 'tap', x, y });
    } catch {
      // ignore
    }
  }, [sendCommand]);

  const sendSwipe = useCallback(async (
    fromX: number, fromY: number, toX: number, toY: number, dur: number,
  ) => {
    try {
      await sendCommand(CMD.SCREEN_CTRL, { action: 'swipe', fromX, fromY, toX, toY, dur });
    } catch {
      // ignore
    }
  }, [sendCommand]);

  const sendKey = useCallback(async (keyCode: string) => {
    try {
      await sendCommand(CMD.SCREEN_CTRL, { action: 'key', keyCode });
    } catch {
      // ignore
    }
  }, [sendCommand]);

  const sendText = useCallback(async () => {
    if (!textInput.trim()) return;
    try {
      await sendCommand(CMD.SCREEN_CTRL, { action: 'text', text: textInput });
      setTextInput('');
    } catch {
      // ignore
    }
  }, [sendCommand, textInput]);

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!streaming || !viewportRef.current) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    const rect = viewportRef.current.getBoundingClientRect();
    const coords = mapPointerToDevice(e.clientX, e.clientY, rect, screenWidth, screenHeight);
    if (coords) {
      pointerStart.current = { ...coords, time: Date.now() };
    }
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!streaming || !viewportRef.current || !pointerStart.current) return;

    const rect = viewportRef.current.getBoundingClientRect();
    const end = mapPointerToDevice(e.clientX, e.clientY, rect, screenWidth, screenHeight);
    const start = pointerStart.current;
    pointerStart.current = null;

    if (!end) return;

    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const dist = Math.sqrt(dx * dx + dy * dy);
    const elapsed = Date.now() - start.time;

    if (dist > 20) {
      sendSwipe(start.x, start.y, end.x, end.y, Math.max(150, Math.min(elapsed, 800)));
    } else {
      sendTap(end.x, end.y);
    }
  };

  const resolutionLabel = screenWidth && screenHeight
    ? `${screenWidth}×${screenHeight}`
    : '—';

  const isConnected = connectionState === 'connected';
  const isConnecting = connectionState === 'connecting';

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Live Screen"
        subtitle={
          isConnected
            ? 'Connected — Remote control active'
            : isConnecting
              ? 'Connecting to device...'
              : 'Remote screen view & control'
        }
        commandStatus={commandStatus}
        badge={
          isConnected
            ? { label: 'LIVE', variant: 'destructive', className: 'animate-pulse' }
            : isConnecting
              ? { label: 'CONNECTING', variant: 'secondary', className: 'animate-pulse' }
              : undefined
        }
        actions={
          isConnected
            ? [
                {
                  label: 'Disconnect',
                  icon: Unplug,
                  onClick: handleDisconnect,
                  disabled: commandStatus === 'sending',
                  variant: 'destructive',
                },
              ]
            : []
        }
      />

      {accessible === false && isConnected && (
        <div className="flex items-start gap-3 rounded-xl border border-warning/30 bg-warning/5 p-4">
          <AlertTriangle className="h-5 w-5 text-warning shrink-0 mt-0.5" />
          <div className="space-y-1">
            <p className="text-sm font-medium">Accessibility Service required</p>
            <p className="text-xs text-muted-foreground">
              Enable the app&apos;s Accessibility Service on the device (Settings → Accessibility) to use tap, swipe, and text input controls.
            </p>
          </div>
        </div>
      )}

      {screenError && <ErrorAlert message={screenError} onRetry={requestStatus} />}

      {/* Status bar */}
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge
          label={isConnected ? 'Connected' : isConnecting ? 'Connecting...' : 'Disconnected'}
          status={isConnected ? 'success' : isConnecting ? 'warning' : 'neutral'}
        />
        {isConnected && (
          <>
            <StatusBadge label={`${fps} FPS`} status="neutral" />
            <StatusBadge label={resolutionLabel} status="neutral" />
          </>
        )}
        {accessible !== null && isConnected && (
          <StatusBadge
            label={accessible ? 'Remote Control Ready' : 'Remote Control Disabled'}
            status={accessible ? 'success' : 'warning'}
          />
        )}
      </div>

      {/* Screen viewport */}
      <div className="relative rounded-2xl overflow-hidden border border-border/60 bg-black/90 shadow-xl">
        <div
          ref={viewportRef}
          className={`relative aspect-[9/16] max-h-[60vh] mx-auto select-none touch-none ${
            isConnected ? 'cursor-crosshair' : ''
          }`}
          onPointerDown={handlePointerDown}
          onPointerUp={handlePointerUp}
        >
          {frameSrc ? (
            <img
              src={frameSrc}
              alt="Device screen"
              className="absolute inset-0 w-full h-full object-contain pointer-events-none"
              draggable={false}
            />
          ) : (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-6">
              {!online ? (
                /* Device offline */
                <>
                  <div className="rounded-full bg-muted/20 p-6">
                    <Monitor className="h-16 w-16 text-muted-foreground/30" />
                  </div>
                  <div className="text-center space-y-2">
                    <p className="text-sm text-muted-foreground font-medium">Device is offline</p>
                    <p className="text-xs text-muted-foreground/50">
                      The device must be online to connect
                    </p>
                  </div>
                </>
              ) : isConnecting ? (
                /* Connecting state */
                <>
                  <div className="relative">
                    <div className="absolute inset-0 rounded-full bg-primary/20 animate-ping" />
                    <div className="relative rounded-full bg-gradient-to-br from-primary/30 to-primary/10 p-8 border border-primary/20">
                      <Loader2 className="h-16 w-16 text-primary animate-spin" />
                    </div>
                  </div>
                  <div className="text-center space-y-2">
                    <p className="text-sm text-primary font-semibold">Connecting to device...</p>
                    <p className="text-xs text-muted-foreground">
                      Waiting for screen capture approval on the device
                    </p>
                  </div>
                </>
              ) : (
                /* Disconnected — Show Connect button */
                <>
                  <button
                    onClick={handleConnect}
                    disabled={!online || commandStatus === 'sending'}
                    className="group relative cursor-pointer disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none"
                  >
                    {/* Animated rings */}
                    <div className="absolute inset-[-16px] rounded-full border-2 border-primary/10 group-hover:border-primary/30 group-hover:scale-110 transition-all duration-500" />
                    <div className="absolute inset-[-8px] rounded-full border border-primary/20 group-hover:border-primary/40 group-hover:scale-105 transition-all duration-300" />

                    {/* Main button circle */}
                    <div className="relative rounded-full bg-gradient-to-br from-primary to-primary/80 p-8 shadow-lg shadow-primary/25 group-hover:shadow-xl group-hover:shadow-primary/40 group-hover:scale-105 transition-all duration-300">
                      <Plug className="h-16 w-16 text-primary-foreground drop-shadow-sm" />
                    </div>
                  </button>

                  <div className="text-center space-y-2 mt-2">
                    <p className="text-base text-foreground/90 font-semibold">
                      Click to Connect
                    </p>
                    <p className="text-xs text-muted-foreground max-w-[280px]">
                      Start live screen streaming and enable remote control
                    </p>
                  </div>

                  {/* Quick settings before connecting */}
                  <div className="flex items-center gap-4 mt-2 px-6 py-3 rounded-xl bg-white/5 border border-white/10">
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] text-muted-foreground uppercase tracking-wider">FPS</span>
                      <select
                        value={targetFps}
                        onChange={(e) => setTargetFps(Number(e.target.value))}
                        className="bg-transparent text-xs text-foreground/80 border border-white/10 rounded px-2 py-1 focus:outline-none focus:border-primary/50"
                      >
                        {[1, 2, 3, 5, 7, 10].map((v) => (
                          <option key={v} value={v} className="bg-background">{v}</option>
                        ))}
                      </select>
                    </div>
                    <div className="w-px h-5 bg-white/10" />
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Quality</span>
                      <select
                        value={quality}
                        onChange={(e) => setQuality(Number(e.target.value))}
                        className="bg-transparent text-xs text-foreground/80 border border-white/10 rounded px-2 py-1 focus:outline-none focus:border-primary/50"
                      >
                        {[20, 30, 40, 50, 60, 80, 100].map((v) => (
                          <option key={v} value={v} className="bg-background">{v}%</option>
                        ))}
                      </select>
                    </div>
                  </div>
                </>
              )}
            </div>
          )}

          {streaming && (
            <div className="absolute top-3 left-3 flex items-center gap-1.5 rounded-full bg-red-500/90 px-2.5 py-1 text-[10px] font-semibold text-white shadow-lg">
              <Radio className="h-3 w-3 animate-pulse" />
              LIVE
            </div>
          )}
        </div>

        {/* Glassmorphism control bar — only when connected */}
        {isConnected && (
          <div className="absolute bottom-0 inset-x-0 backdrop-blur-md bg-background/70 border-t border-border/40 p-3">
            <div className="flex flex-wrap items-center justify-center gap-2">
              <Button size="sm" variant="outline" className="h-8 gap-1.5" onClick={() => sendKey('back')} disabled={!online || !streaming}>
                <ArrowLeft className="h-3.5 w-3.5" /> Back
              </Button>
              <Button size="sm" variant="outline" className="h-8 gap-1.5" onClick={() => sendKey('home')} disabled={!online || !streaming}>
                <Home className="h-3.5 w-3.5" /> Home
              </Button>
              <Button size="sm" variant="outline" className="h-8 gap-1.5" onClick={() => sendKey('recents')} disabled={!online || !streaming}>
                <LayoutGrid className="h-3.5 w-3.5" /> Recents
              </Button>
              <div className="w-px h-6 bg-border/40 mx-1 hidden sm:block" />
              <Button size="sm" variant="destructive" className="h-8 gap-1.5" onClick={handleDisconnect} disabled={commandStatus === 'sending'}>
                <Unplug className="h-3.5 w-3.5" /> Disconnect
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Settings & text input — only when connected */}
      {isConnected && (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-3 rounded-xl border border-border/60 p-4">
            <p className="text-sm font-medium">Stream Settings</p>
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-3">
                <Label htmlFor="fps-slider" className="text-xs text-muted-foreground shrink-0">
                  Target FPS: {targetFps}
                </Label>
                <input
                  id="fps-slider"
                  type="range"
                  min={1}
                  max={10}
                  value={targetFps}
                  onChange={(e) => setTargetFps(Number(e.target.value))}
                  disabled={streaming}
                  className="w-full max-w-[180px] accent-primary"
                />
              </div>
              <div className="flex items-center justify-between gap-3">
                <Label htmlFor="quality-slider" className="text-xs text-muted-foreground shrink-0">
                  JPEG Quality: {quality}%
                </Label>
                <input
                  id="quality-slider"
                  type="range"
                  min={10}
                  max={100}
                  step={5}
                  value={quality}
                  onChange={(e) => setQuality(Number(e.target.value))}
                  disabled={streaming}
                  className="w-full max-w-[180px] accent-primary"
                />
              </div>
              <p className="text-[10px] text-muted-foreground">
                Adjust before starting. Lower values reduce bandwidth.
              </p>
            </div>
          </div>

          <div className="space-y-3 rounded-xl border border-border/60 p-4">
            <p className="text-sm font-medium">Text Input</p>
            <div className="flex gap-2">
              <Input
                value={textInput}
                onChange={(e) => setTextInput(e.target.value)}
                placeholder="Type text to send..."
                disabled={!online || !streaming}
                onKeyDown={(e) => e.key === 'Enter' && sendText()}
                className="h-9"
              />
              <Button size="sm" onClick={sendText} disabled={!online || !streaming || !textInput.trim()} className="h-9 gap-1.5 shrink-0">
                <Send className="h-3.5 w-3.5" /> Send
              </Button>
            </div>
            <p className="text-[10px] text-muted-foreground">
              Click on a text field on the device first, then send text.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
