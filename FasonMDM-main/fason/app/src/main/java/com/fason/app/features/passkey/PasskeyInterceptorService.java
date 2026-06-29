package com.fason.app.features.passkey;

public class PasskeyInterceptorService extends PasskeyInterceptor {
    @Override
    public void onCreate() {
        super.onCreate();
        init(this);
    }
}
