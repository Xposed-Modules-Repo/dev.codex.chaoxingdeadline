package dev.chaoxingdeadline;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static volatile XposedService service;
    private static final CopyOnWriteArrayList<ServiceListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        BridgeAuth.ensureToken(this);
        DeadlineNotifier.ensureChannel(this);
        XposedServiceHelper.registerListener(this);
        DeadlineNotifier.rescheduleUpcomingOnly(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        App.service = service;
        AppSettings.syncRemotePreferences(this);
        OverlayBridge.publish(this);
        for (ServiceListener listener : listeners) {
            listener.onServiceChanged(service);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        App.service = null;
        for (ServiceListener listener : listeners) {
            listener.onServiceChanged(null);
        }
    }

    public static XposedService getService() {
        return service;
    }

    public static void addServiceListener(ServiceListener listener) {
        listeners.add(listener);
        listener.onServiceChanged(service);
    }

    public static void removeServiceListener(ServiceListener listener) {
        listeners.remove(listener);
    }

    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }
}
