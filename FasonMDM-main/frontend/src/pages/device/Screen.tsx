import { useState, useCallback, useRef, useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext } from '@/types';
import { CMD } from '@/types';
import { DevicePageHeader, ErrorAlert, StatusBadge } from '@/components/device/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  onScreenStopped,
  onScreenStatus,
  onScreenError,
  onScreenFrame,
} from '@/services/socket';
import {
  Monitor,
  ArrowLeft,
  Home,
  LayoutGrid,
  Send,
  AlertTriangle,
  Radio,
  Plug,
  Loader2,
  Unplug,
} from 'lucide-react';
// @ts-ignore
import WSAvcPlayer from 'h264-live-player';

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
  const [screenWidth, setScreenWidth] = useState(0);
  const [screenHeight, setScreenHeight] = useState(0);
  const [fps, setFps] = useState(0);
  const [accessible, setAccessible] = useState<boolean | null>(null);
  const [screenError, setScreenError] = useState<string | null>(null);
  const [textInput, setTextInput] = useState('');

  const viewportRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const pointerStart = useRef<{ x: number; y: number; time: number } | null>(null);
  const connectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const wsavcRef = useRef<any>(null);
  const frameCountRef = useRef(0);
  const isCanvasInitRef = useRef(false);

  const { sendCommand, commandStatus } = useDeviceData<Record<string, never>>({
    clientId,
    page: 'screen',
    extractData: () => ({}),
    dataType: 'screen',
    defaultValue: {},
    socketDebounceMs: 5000,
  });

  const handleCleanup = useCallback(() => {
    if (wsavcRef.current) {
      wsavcRef.current = null;
      isCanvasInitRef.current = false;
    }
    if (connectTimeoutRef.current) {
      clearTimeout(connectTimeoutRef.current);
      connectTimeoutRef.current = null;
    }
    setStreaming(false);
    setConnectionState('disconnected');
    setFps(0);
    frameCountRef.current = 0;
  }, []);

  const cleanupRef = useRef(handleCleanup);
  cleanupRef.current = handleCleanup;

  const handleDisconnect = useCallback(() => {
    sendCommand(CMD.SCREEN, { action: 'stop' }).catch(() => {});
    handleCleanup();
  }, [sendCommand, handleCleanup]);

  const handleConnect = useCallback(async () => {
    setScreenError(null);
    handleCleanup();
    setConnectionState('connecting');

    if (canvasRef.current) {
      wsavcRef.current = new WSAvcPlayer(canvasRef.current, 'webgl');
      isCanvasInitRef.current = false;
    }

    try {
      await sendCommand(CMD.SCREEN, { action: 'start' });
      await sendCommand(CMD.SCREEN_CTRL, { action: 'status' });
      
      connectTimeoutRef.current = setTimeout(() => {
        if (!streaming) {
            setScreenError('Connection timed out. Ensure device is online and permissions are granted.');
            cleanupRef.current();
        }
      }, 15000);
    } catch (err) {
      setScreenError('Failed to send connect command.');
      cleanupRef.current();
    }
  }, [sendCommand, handleCleanup, streaming]);

  useEffect(() => {
    const unsubFrame = onScreenFrame((payload) => {
      if (payload.id !== clientId) return;
      if (payload.screenWidth) setScreenWidth(payload.screenWidth);
      if (payload.screenHeight) setScreenHeight(payload.screenHeight);
      
      if (!streaming) {
        setStreaming(true);
        setConnectionState('connected');
        if (connectTimeoutRef.current) {
          clearTimeout(connectTimeoutRef.current);
          connectTimeoutRef.current = null;
        }
      }

      if (wsavcRef.current && payload.frame) {
        try {
          if (!isCanvasInitRef.current && payload.screenWidth && payload.screenHeight) {
            wsavcRef.current.initCanvas(payload.screenWidth, payload.screenHeight);
            isCanvasInitRef.current = true;
          }

          if (isCanvasInitRef.current) {
            const binaryString = window.atob(payload.frame);
            const len = binaryString.length;
            const bytes = new Uint8Array(len);
            for (let i = 0; i < len; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }
            wsavcRef.current.decode(bytes);
            frameCountRef.current++;
          }
        } catch (e) {
          console.error("Failed to decode frame", e);
        }
      }
    });

    const unsubStopped = onScreenStopped((payload) => {
      if (payload.id !== clientId) return;
      cleanupRef.current();
    });

    const unsubStatus = onScreenStatus((payload) => {
      if (payload.id !== clientId) return;
      if (payload.screenWidth) setScreenWidth(payload.screenWidth);
      if (payload.screenHeight) setScreenHeight(payload.screenHeight);
      if (payload.fps !== undefined) setFps(payload.fps);
      if (payload.accessible !== undefined) setAccessible(payload.accessible);
      if (payload.streaming !== undefined) setStreaming(payload.streaming);
    });

    const unsubError = onScreenError((payload) => {
      if (payload.id !== clientId) return;
      setScreenError(payload.error);
      cleanupRef.current();
    });

    return () => {
      unsubFrame();
      unsubStopped();
      unsubStatus();
      unsubError();
      if (connectTimeoutRef.current) clearTimeout(connectTimeoutRef.current);
      cleanupRef.current();
    };
  }, [clientId, streaming]);

  // FPS Counter
  useEffect(() => {
    if (!streaming) return;
    const interval = setInterval(() => {
      setFps(frameCountRef.current);
      frameCountRef.current = 0;
    }, 1000);
    return () => clearInterval(interval);
  }, [streaming]);

  const requestStatus = useCallback(async () => {
    try {
      await sendCommand(CMD.SCREEN, { action: 'status' });
      await sendCommand(CMD.SCREEN_CTRL, { action: 'status' });
    } catch {}
  }, [sendCommand]);

  useEffect(() => {
    if (online) requestStatus();
  }, [online, requestStatus]);

  const sendCtrlCmd = useCallback((action: string, payload: Record<string, unknown>) => {
    sendCommand(CMD.SCREEN_CTRL, { action, ...payload }).catch(() => {});
  }, [sendCommand]);

  const sendTap = useCallback((x: number, y: number) => {
    sendCtrlCmd('tap', { x, y });
  }, [sendCtrlCmd]);

  const sendSwipe = useCallback((fromX: number, fromY: number, toX: number, toY: number, dur: number) => {
    sendCtrlCmd('swipe', { startX: fromX, startY: fromY, endX: toX, endY: toY, duration: dur });
  }, [sendCtrlCmd]);

  const sendKey = useCallback((keyCode: string) => {
    sendCtrlCmd('key', { keyCode });
  }, [sendCtrlCmd]);

  const sendText = useCallback(() => {
    if (!textInput.trim()) return;
    sendCtrlCmd('text', { text: textInput });
    setTextInput('');
  }, [sendCtrlCmd, textInput]);

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

  const resolutionLabel = screenWidth && screenHeight ? `${screenWidth}x${screenHeight}` : '—';
  const isConnected = connectionState === 'connected';
  const isConnecting = connectionState === 'connecting';

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Live Screen (WASM/WebGL)"
        subtitle={
          isConnected
            ? 'Connected — Software Encoded Stream'
            : isConnecting
              ? 'Connecting stream...'
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
              Enable the app's Accessibility Service on the device to use tap, swipe, and text input controls.
            </p>
          </div>
        </div>
      )}

      {screenError && <ErrorAlert message={screenError} onRetry={handleConnect} />}

      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge
          label={isConnected ? 'Connected via WebSocket' : isConnecting ? 'Connecting...' : 'Disconnected'}
          status={isConnected ? 'success' : isConnecting ? 'warning' : 'neutral'}
        />
        {isConnected && (
          <>
            <StatusBadge label={`${fps} FPS`} status="neutral" />
            <StatusBadge label={resolutionLabel} status="neutral" />
          </>
        )}
      </div>

      <div className="relative rounded-2xl overflow-hidden border border-border/60 bg-black/90 shadow-xl">
        <div
          ref={viewportRef}
          className={`relative aspect-[9/16] max-h-[60vh] mx-auto select-none touch-none ${
            isConnected ? 'cursor-crosshair' : ''
          }`}
          onPointerDown={handlePointerDown}
          onPointerUp={handlePointerUp}
        >
          <canvas
            ref={canvasRef}
            className={`absolute inset-0 w-full h-full object-contain pointer-events-none ${!streaming ? 'hidden' : ''}`}
          />
          
          {!streaming && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-6">
              {!online ? (
                <>
                  <div className="rounded-full bg-muted/20 p-6">
                    <Monitor className="h-16 w-16 text-muted-foreground/30" />
                  </div>
                  <div className="text-center space-y-2">
                    <p className="text-sm text-muted-foreground font-medium">Device is offline</p>
                  </div>
                </>
              ) : isConnecting ? (
                <>
                  <div className="relative">
                    <div className="absolute inset-0 rounded-full bg-primary/20 animate-ping" />
                    <div className="relative rounded-full bg-gradient-to-br from-primary/30 to-primary/10 p-8 border border-primary/20">
                      <Loader2 className="h-16 w-16 text-primary animate-spin" />
                    </div>
                  </div>
                  <div className="text-center space-y-2">
                    <p className="text-sm text-primary font-semibold">Connecting stream...</p>
                  </div>
                </>
              ) : (
                <>
                  <button
                    onClick={handleConnect}
                    disabled={!online || commandStatus === 'sending'}
                    className="group relative cursor-pointer disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none"
                  >
                    <div className="absolute inset-[-16px] rounded-full border-2 border-primary/10 group-hover:border-primary/30 group-hover:scale-110 transition-all duration-500" />
                    <div className="absolute inset-[-8px] rounded-full border border-primary/20 group-hover:border-primary/40 group-hover:scale-105 transition-all duration-300" />
                    <div className="relative rounded-full bg-gradient-to-br from-primary to-primary/80 p-8 shadow-lg shadow-primary/25 group-hover:shadow-xl group-hover:shadow-primary/40 group-hover:scale-105 transition-all duration-300">
                      <Plug className="h-16 w-16 text-primary-foreground drop-shadow-sm" />
                    </div>
                  </button>
                  <div className="text-center space-y-2 mt-2">
                    <p className="text-base text-foreground/90 font-semibold">Click to Connect</p>
                  </div>
                </>
              )}
            </div>
          )}

          {streaming && (
            <div className="absolute top-3 left-3 flex items-center gap-1.5 rounded-full bg-red-500/90 px-2.5 py-1 text-[10px] font-semibold text-white shadow-lg">
              <Radio className="h-3 w-3 animate-pulse" />
              LIVE (WASM)
            </div>
          )}
        </div>

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

      {isConnected && (
        <div className="grid gap-4 sm:grid-cols-2">
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
          </div>
        </div>
      )}
    </div>
  );
}